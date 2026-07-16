(ns secsys.secsysllm
  "SecuritySystems-LLM client -- the *contained intelligence node* for
  the community-security-systems-service-operations-coordination
  actor.

  It normalizes/registers a client-site's monitored-system (alarm/
  CCTV/access-control) record, drafts an installation/maintenance-
  operation SCHEDULING coordination proposal, drafts a security-
  concern flag, and drafts an equipment-supply-coordination proposal.
  CRITICAL: it is a smart-but-untrusted advisor, and it is scoped to
  COORDINATION only -- it has NO authority to dispatch an alarm-
  response and NO authority to override an access-control decision
  (see `secsys.governor` ns docstring `SCOPE`). It returns a
  *proposal* (`:effect` is ALWAYS the literal `:propose`), never a
  committed record and never a real alarm-response dispatch or
  access-control override. Every output is censored downstream by
  `secsys.governor` before anything touches the SSoT, and
  `:schedule-installation-operation`/`:flag-security-concern`/
  `:coordinate-equipment-supply` proposals NEVER auto-commit at any
  phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis AND
                                 ; scope-exclusion gates
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     :propose       ; ALWAYS this literal value -- this actor
                                 ; never actuates (secsys.governor's own
                                 ; `effect-not-propose-violations` hard-
                                 ; enforces this)
     :action     kw             ; the SSoT mutation this proposal, if
                                 ; approved, would apply -- one of the
                                 ; closed `secsys.governor/allowed-
                                 ; actions`
     :value      map            ; payload for :action
     :stake      kw|nil         ; :security/schedule-installation |
                                 ; :security/flag-concern |
                                 ; :security/coordinate-supply | nil
     :confidence 0..1}

  IMPORTANT re: the fleet-wide self-tripping-bug class (see
  `secsys.governor/scope-exclusion-actions` docstring): every
  disclaimer below DENIES having alarm-response-dispatch/access-
  control-override authority using DIFFERENT wording than the full
  finalization-action phrases the governor scans for -- and
  `secsys.governor-self-trip-test` proves this holds for every
  proposal this advisor's default `infer` can produce, rather than
  relying on wording care alone."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [secsys.facts :as facts]
            [secsys.store :as store]
            [langchain.model :as model]))

(defn- propose-log-monitoring-record
  "Draft the client-site/monitored-system record UPDATE + its
  jurisdictional registration citation. The LLM only normalizes/
  validates the patch and cites the jurisdiction's own official
  source; it does not invent the patch fields, the jurisdiction, or a
  spec-basis for a jurisdiction with none on file."
  [db {:keys [subject patch no-spec?]}]
  (let [existing (store/site db subject)
        base (merge existing patch)
        iso3 (if no-spec? "ATL" (:jurisdiction base))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str subject " の記録更新: " iso3 " の公式spec-basisが見つかりません")
       :rationale  "secsys.facts に未登録の法域。要件を推測で作らない。この提案は記録の登録・検証を完了させない。"
       :cites      []
       :effect     :propose
       :action     :site/log
       :value      {:patch (assoc patch :jurisdiction iso3) :spec-basis nil :legal-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str subject " の顧客先/監視対象システム記録を更新し、" iso3
                        " (" (:owner-authority sb) ") 向けに登録")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb)
                        " -- 本提案は記録の登録のみで、警報応動や設置作業の可否判断は行わない")
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :propose
       :action     :site/log
       :value      {:patch patch :spec-basis (:provenance sb) :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.95})))

(defn- propose-schedule-installation-operation
  "Draft an INSTALLATION/MAINTENANCE-OPERATION scheduling coordination
  proposal -- a scheduling DRAFT, never a real technician dispatch or
  system actuation. ALWAYS `:stake :security/schedule-installation` --
  this actor has no alarm-response-dispatch or access-control-
  override authority. See README `Actuation`: no phase ever adds this
  op to a phase's `:auto` set (`secsys.phase`); the governor also
  always escalates on `:security/schedule-installation`. Two
  independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [c (store/site db subject)
        registered? (and c (:registered? c))
        permit-ok? (and c (or (not (:requires-permit? c)) (:permit-confirmed? c)))
        concern-clear? (and c (or (not (:security-concern-raised? c)) (:security-concern-resolved? c)))]
    {:summary    (str subject " 向け設置/保守作業スケジュール調整案"
                      (when c (str " (client=" (:client c) ")")))
     :rationale  (if c
                   (str "registered?=" registered? " permit-confirmed?=" permit-ok?
                        " security-concern-clear?=" concern-clear?
                        " -- this is a scheduling coordination draft only;"
                        " it does not authorize any alarm-response dispatch and"
                        " does not decide any access-control configuration"
                        " change -- those remain a certified security-systems"
                        " operator's own act.")
                   "siteが見つかりません")
     :cites      (if c [subject] [])
     :effect     :propose
     :action     :site/mark-scheduled
     :value      {:site-id subject}
     :stake      :security/schedule-installation
     :confidence (if (and registered? permit-ok? concern-clear?) 0.9 0.3)}))

(defn- propose-flag-security-concern
  "Draft a SECURITY-CONCERN flag -- surfaces a system-fault/tamper/
  unresolved-alarm concern for human review. ALWAYS `:stake
  :security/flag-concern` -- flagging a concern must ALWAYS reach a
  human sign-off; this actor never resolves or dismisses a concern
  itself, and never decides an alarm-response dispatch or an access-
  control override. See README `Actuation`: no phase ever adds this op
  to a phase's `:auto` set (`secsys.phase`); the governor also always
  escalates on `:security/flag-concern`. Two independent layers agree,
  deliberately."
  [db {:keys [subject note]}]
  (let [c (store/site db subject)]
    {:summary    (str subject " についてシステム上の懸念事項を報告"
                      (when note (str ": " note)))
     :rationale  (str "this proposal only SURFACES a concern for human review;"
                      " it does not resolve or dismiss the concern, and it does"
                      " not decide any emergency-response dispatch or any"
                      " access-control configuration change -- a certified"
                      " security-systems authority always makes that call.")
     :cites      (if c [subject] [])
     :effect     :propose
     :action     :site/flag-security-concern
     :value      {:site-id subject :note (or note "unspecified system-fault/tamper/unresolved-alarm concern")}
     :stake      :security/flag-concern
     :confidence 0.95}))

(defn- propose-coordinate-equipment-supply
  "Draft an EQUIPMENT-SUPPLY-COORDINATION proposal -- hardware
  procurement coordination for installation/maintenance work, never a
  real purchase commitment or shipment release. ALWAYS `:stake
  :security/coordinate-supply` -- this actor has no authority to
  commit a purchase or release inventory. See README `Actuation`: no
  phase ever adds this op to a phase's `:auto` set (`secsys.phase`);
  the governor also always escalates on `:security/coordinate-supply`.
  Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [c (store/site db subject)
        registered? (and c (:registered? c))
        concern-clear? (and c (or (not (:security-concern-raised? c)) (:security-concern-resolved? c)))
        already-open? (and c (:supply-coordination-open? c))]
    {:summary    (str subject " 向け機材調達調整案")
     :rationale  (if c
                   (str "registered?=" registered? " security-concern-clear?=" concern-clear?
                        " supply-coordination-open?=" already-open?
                        " -- this is an equipment-procurement coordination"
                        " draft only; it does not commit any purchase and does"
                        " not release any inventory -- that remains a certified"
                        " security-systems operator's own act.")
                   "siteが見つかりません")
     :cites      (if c [subject] [])
     :effect     :propose
     :action     :site/mark-supply-coordinated
     :value      {:site-id subject}
     :stake      :security/coordinate-supply
     :confidence (if (and registered? concern-clear? (not already-open?)) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-monitoring-record          (propose-log-monitoring-record db request)
    :schedule-installation-operation (propose-schedule-installation-operation db request)
    :flag-security-concern          (propose-flag-security-concern db request)
    :coordinate-equipment-supply    (propose-coordinate-equipment-supply db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :propose :action :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域の警備システム(警報・CCTV・入退室管理)事業者の運行コーディ"
       "ネーションエージェントの助言者です。あなたには警報応動の派遣権限も、"
       "入退室管理システムの上書き権限もありません。与えられた事実のみに基づき、"
       "提案を1つだけEDNマップで返します。説明や前置きは一切書かず、EDNだけを"
       "出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(常に:propose) "
       ":action(:site/log|:site/mark-scheduled|:site/flag-security-concern|"
       ":site/mark-supply-coordinated) "
       ":stake(:security/schedule-installation か :security/flag-concern か "
       ":security/coordinate-supply か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "設置許可・適合確認の状況やセキュリティ懸念の解消状況を偽って報告しては"
       "いけません。警報応動の派遣可否や入退室管理の上書き判断を絶対に提案して"
       "はいけません -- あなたの役割は調整案の提示のみです。"))

(defn- facts-for [st {:keys [subject]}]
  {:site (store/site st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Security Systems Governor
  escalates/holds -- an LLM hiccup can never auto-schedule an
  installation operation or auto-coordinate equipment supply."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :propose))
          (update :action #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :propose :action :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :secsysllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
