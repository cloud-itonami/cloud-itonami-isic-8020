(ns secsys.governor
  "Security Systems Governor -- the independent compliance layer that
  earns the SecuritySystems-LLM the right to commit. The LLM has no
  notion of jurisdictional security-systems-services licensing law,
  whether a site's own client/monitored-system record has actually
  been independently verified/registered, whether a permit-requiring
  installation's compliance check has actually been confirmed,
  whether an open security concern has actually been resolved, or
  when an act stops being a draft coordination note and becomes
  something this actor must NEVER be allowed to represent (a real
  alarm-response dispatch decision or a real access-control-override
  decision), so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  ================================================================
  SCOPE, stated as a structural invariant, not a policy preference
  ================================================================
  This actor is a security-systems-services OPERATIONS COORDINATION
  actor. It is NOT the alarm-response dispatcher and NOT the access-
  control-override authority -- actual alarm-response dispatch (e.g.
  deciding to dispatch police/fire in response to an alarm) is always
  a human/emergency-services decision, never this actor's, and neither
  is finalizing an access-control-override decision. Every proposal it
  can ever produce has a literal `:effect :propose` (never an
  actuation) and an `:action` drawn from a FOUR-MEMBER closed
  allowlist (`secsys.governor/allowed-actions`) that maps 1:1 to the
  FOUR ops in `secsys.governor/allowed-ops`
  (`:log-monitoring-record`/`:schedule-installation-operation`/
  `:flag-security-concern`/`:coordinate-equipment-supply`). A proposal
  to directly finalize an alarm-response-dispatch decision or an
  access-control-override decision is not merely disallowed by policy
  -- it CANNOT be represented in this closed allowlist at all, so
  `action-allowlist-violations` hard-blocks it structurally even if an
  advisor somehow proposed one. `scope-exclusion-violations` is a
  SECOND, independent layer: it text-scans the proposal's own
  rationale/summary for a small set of finalization/execution ACTION
  phrases (never a bare noun -- see that check's own docstring for
  why) so a proposal that merely NAMES a forbidden finalization act in
  its prose (without setting a matching `:action`) is caught too. Two
  independent layers, matching the two-layer discipline every prior
  sibling actor's own real-actuation gate uses (see `high-stakes`
  below and `secsys.phase`).

  `:flag-security-concern` in particular must ALWAYS escalate to human
  sign-off -- it is never a member of any phase's `:auto` set, at any
  phase; this actor never resolves or dismisses a concern itself.

  Ten checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them. The confidence/actuation gate is SOFT: it asks
  a human to look (low confidence / high-stakes), and the human may
  approve -- but see `secsys.phase`: `:schedule-installation-
  operation`/`:flag-security-concern`/`:coordinate-equipment-supply`
  are NEVER in any phase's `:auto` set either. Two independent layers
  agree that these three ops always need a human.

    1.  Effect-not-:propose      -- structural: every proposal this
                                     actor emits must carry the literal
                                     `:effect :propose` -- it never
                                     actuates.
    2.  Op not allowlisted       -- the request's `:op` must be one of
                                     the four closed-allowlist ops.
    3.  Action not allowlisted   -- the proposal's `:action` must be
                                     one of the four closed-allowlist
                                     actions -- structurally excludes
                                     any alarm-response-dispatch or
                                     access-control-override action
                                     (see SCOPE above).
    4.  Scope-exclusion          -- the proposal's own rationale/
                                     summary text must not name a
                                     finalization/execution ACTION this
                                     actor must never perform (SECOND,
                                     independent layer to #3).
    5.  Spec-basis               -- for `:log-monitoring-record`, did
                                     the advisor cite an OFFICIAL
                                     source (`secsys.facts`), or invent
                                     one?
    6.  Record not verified      -- for `:schedule-installation-
                                     operation`/`:flag-security-
                                     concern`/`:coordinate-equipment-
                                     supply`, has the subject site's
                                     own client/monitored-system record
                                     actually been independently
                                     verified/registered (via a
                                     committed `:log-monitoring-
                                     record`)? The HARD invariant this
                                     vertical's own README states:
                                     'a system/client-site record must
                                     be independently verified/
                                     registered before any action' --
                                     applied to ALL THREE non-
                                     registration ops, not only the
                                     highest-stakes one.
    7.  Installation-permit
        unconfirmed                -- for `:schedule-installation-
                                     operation`, INDEPENDENTLY verify
                                     that if the site requires an
                                     installation permit/compliance
                                     check, its own `:permit-
                                     confirmed?` fact is true. Never
                                     trust the advisor's self-reported
                                     confidence alone.
    8.  Open security concern    -- for `:schedule-installation-
                                     operation`/`:coordinate-equipment-
                                     supply`, an unresolved security
                                     concern on file for the subject
                                     site (`:security-concern-raised?
                                     true` AND `:security-concern-
                                     resolved? false`) is a HARD,
                                     un-overridable hold.
    9.  Already scheduled        -- for `:schedule-installation-
                                     operation`, refuses to double-
                                     schedule the SAME site, off a
                                     dedicated `:scheduled?` fact
                                     (never a `:status` value).
    10. Already coordinating     -- for `:coordinate-equipment-
                                     supply`, refuses to open a SECOND
                                     equipment-supply-coordination
                                     request while one is already
                                     open, off a dedicated `:supply-
                                     coordination-open?` fact (never a
                                     `:status` value)."
  (:require [clojure.string :as str]
            [secsys.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed op allowlist -- see this ns docstring `SCOPE`. Nothing
  outside this four-member set is a valid `:op`, structurally."
  #{:log-monitoring-record :schedule-installation-operation
    :flag-security-concern :coordinate-equipment-supply})

(def allowed-actions
  "The closed `:action` allowlist -- 1:1 with `allowed-ops`. An
  alarm-response-dispatch action or an access-control-override action
  is not a member of this set and can therefore never be represented
  as a proposal `:action` this governor would let through."
  #{:site/log :site/mark-scheduled
    :site/flag-security-concern :site/mark-supply-coordinated})

(def high-stakes
  "Stakes grave enough to always require a human, even when the
  governor is otherwise clean. Scheduling a real installation
  operation, flagging a security concern, and coordinating equipment
  supply are the three ops this actor never auto-commits, at any
  phase -- `:log-monitoring-record` (pure data logging, no
  operational/dispatch weight) is the ONLY auto-eligible op, matching
  every sibling actor's own 'only ONE member' phase-3 `:auto` set
  discipline."
  #{:security/schedule-installation :security/flag-concern
    :security/coordinate-supply})

;; ------------------------- scope-exclusion terms -------------------------

(def scope-exclusion-actions
  "Finalization/execution ACTION phrases (never a bare noun) naming an
  alarm-response-dispatch decision or an access-control-override
  decision this actor must NEVER finalize. This fleet has
  independently rediscovered, in multiple sibling repos, the SAME bug
  class: a scope-exclusion term list phrased as a bare noun (e.g.
  \"response\", \"dispatch\", \"override\") accidentally matches
  inside this actor's OWN default mock-advisor's disclaimer text for a
  legitimate, allowed proposal (every disclaimer in
  `secsys.secsysllm` says things like 'this proposal does not
  authorize any alarm-response dispatch' -- a bare noun like
  \"response\" or \"dispatch\" would match that sentence and self-
  block the happy path). Phrasing each term as the FULL finalization-
  action phrase avoids this: a disclaimer that merely DENIES having
  the authority never contains the literal action phrase itself as a
  contiguous substring. `secsys.governor-self-trip-test` exercises
  every default proposal this actor's advisor can produce and asserts
  NONE of them trip this check -- that test, not careful wording
  alone, is the real guarantee."
  ["dispatch police response for this alarm"
   "dispatch fire response for this alarm"
   "dispatch emergency responders to this alarm"
   "authorize dispatch of an alarm response"
   "override the access-control system for this site"
   "override the access-control lock for this door"
   "finalize the access-control override"
   "grant emergency access override for this site"
   "unlock the access-control system remotely"
   "clear this alarm as a false alarm without verification"])

;; ----------------------------- checks -----------------------------

(defn- effect-not-propose-violations
  "Every proposal this actor emits must carry the literal `:effect
  :propose` -- it never actuates. Evaluated UNCONDITIONALLY, on every
  op."
  [_request proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str "提案の:effectが:proposeではありません(" (:effect proposal) ")")}]))

(defn- op-allowlist-violations
  "The request's `:op` must be one of the four closed-allowlist ops.
  Evaluated UNCONDITIONALLY."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowlisted
      :detail (str op " は許可された操作(op)一覧に含まれません")}]))

(defn- action-allowlist-violations
  "The proposal's `:action` must be one of the four closed-allowlist
  actions -- structurally excludes any alarm-response-dispatch or
  access-control-override action. Evaluated UNCONDITIONALLY."
  [_request proposal]
  (when-not (contains? allowed-actions (:action proposal))
    [{:rule :action-not-allowlisted
      :detail (str (:action proposal) " は許可されたaction一覧に含まれません -- 警報応動派遣/入退室管理上書きの確定操作は決して許可されない")}]))

(defn- scope-exclusion-violations
  "The proposal's own rationale/summary text must not name a
  finalization/execution ACTION this actor must never perform -- see
  `scope-exclusion-actions` docstring for why these are phrased as
  full action phrases, never bare nouns. Evaluated UNCONDITIONALLY."
  [_request proposal]
  (let [text (str/lower-case (str (:summary proposal) " " (:rationale proposal)))]
    (when (some #(str/includes? text (str/lower-case %)) scope-exclusion-actions)
      [{:rule :scope-exclusion-violation
        :detail "提案文言が警報応動派遣/入退室管理上書きの確定行為に該当する表現を含みます -- 恒久的にブロック"}])))

(defn- spec-basis-violations
  "A `:log-monitoring-record` proposal with no spec-basis citation is a
  HARD violation -- never invent a jurisdiction's security-systems-
  services licensing/registration requirements."
  [{:keys [op]} proposal]
  (when (= op :log-monitoring-record)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- record-not-verified-violations
  "For `:schedule-installation-operation`/`:flag-security-concern`/
  `:coordinate-equipment-supply`, the subject site's own client/
  monitored-system record must have ALREADY been independently
  verified/registered (a committed `:log-monitoring-record`) -- the
  HARD invariant this vertical's own README states applies before ANY
  of these three ops, not only the highest-stakes one."
  [{:keys [op subject]} st]
  (when (contains? #{:schedule-installation-operation :flag-security-concern :coordinate-equipment-supply} op)
    (let [c (store/site st subject)]
      (when-not (true? (:registered? c))
        [{:rule :record-not-verified
          :detail (str subject " の顧客先/監視対象システム記録が未登録・未検証の状態での提案")}]))))

(defn- installation-permit-violations
  "For `:schedule-installation-operation`, INDEPENDENTLY verify that if
  the site requires an installation permit/compliance check, its own
  `:permit-confirmed?` fact is true. Evaluated UNCONDITIONALLY (every
  schedule proposal for a permit-requiring site needs a confirmed
  permit/compliance check)."
  [{:keys [op subject]} st]
  (when (= op :schedule-installation-operation)
    (let [c (store/site st subject)]
      (when (and (:requires-permit? c) (not (true? (:permit-confirmed? c))))
        [{:rule :installation-permit-unconfirmed
          :detail (str subject " は設置許可/適合確認が必要だが、確認が未完了 -- 設置スケジュール提案は進められない")}]))))

(defn- open-security-concern-violations
  "An unresolved security concern -- already on file for the subject
  site (`:security-concern-raised? true` AND `:security-concern-
  resolved? false`) -- is a HARD, un-overridable hold. Evaluated
  UNCONDITIONALLY across `:schedule-installation-operation` and
  `:coordinate-equipment-supply`."
  [{:keys [op subject]} st]
  (when (contains? #{:schedule-installation-operation :coordinate-equipment-supply} op)
    (let [c (store/site st subject)]
      (when (and (true? (:security-concern-raised? c)) (not (true? (:security-concern-resolved? c))))
        [{:rule :open-security-concern
          :detail (str subject " は未解決のセキュリティ懸念がある -- 設置スケジュール/機材調達調整提案は進められない")}]))))

(defn- already-scheduled-violations
  "For `:schedule-installation-operation`, refuses to double-schedule
  the SAME site, off a dedicated `:scheduled?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-installation-operation)
    (when (store/site-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既に設置スケジュールが調整済み")}])))

(defn- already-coordinating-violations
  "For `:coordinate-equipment-supply`, refuses to open a SECOND
  equipment-supply-coordination request while one is already open, off
  a dedicated `:supply-coordination-open?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :coordinate-equipment-supply)
    (when (store/site-supply-already-open? st subject)
      [{:rule :already-coordinating
        :detail (str subject " は既に機材調達調整が進行中")}])))

(defn check
  "Censors a SecuritySystems-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (effect-not-propose-violations request proposal)
                           (op-allowlist-violations request proposal)
                           (action-allowlist-violations request proposal)
                           (scope-exclusion-violations request proposal)
                           (spec-basis-violations request proposal)
                           (record-not-verified-violations request st)
                           (installation-permit-violations request st)
                           (open-security-concern-violations request st)
                           (already-scheduled-violations request st)
                           (already-coordinating-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
