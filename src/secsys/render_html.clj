(ns secsys.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for `cloud-itonami-isic-8020`: this repo previously had NO demo page
  and no generator at all.

  ================================================================
  Everything on the page comes out of a REAL actor run
  ================================================================
  `run-demo!` builds the REAL OperationActor (`secsys.operation/build`
  -> a compiled langgraph-clj StateGraph) over a REAL seeded
  `secsys.store/MemStore` and drives it with `langgraph.graph/run*`,
  exactly the way `secsys.sim` does. Every proposal is produced by the
  shipped advisor (`secsys.secsysllm/mock-advisor`), censored by the
  REAL `secsys.governor`, gated by the REAL `secsys.phase`, and
  committed (or held) by the REAL graph nodes. Nothing on this page is
  a hand-written table of what the actor *would* do:

    - the site table is `store/all-sites` AFTER the run;
    - the operations table is the per-run `:audit` channel the graph
      itself accumulated;
    - the HARD-hold table is `store/ledger` filtered to
      `:t :governor-hold`, and each rule name / detail string is the
      governor's own `:violations` entry;
    - the phase-gate table is `secsys.phase/phases` rendered as data;
    - the closed allowlists are `secsys.governor/allowed-ops` /
      `allowed-actions` / `high-stakes` / `scope-exclusion-actions`;
    - the jurisdiction table is `secsys.facts/catalog` +
      `secsys.facts/coverage`;
    - the draft registry records are `store/schedule-history` /
      `store/supply-history`.

  Where a value genuinely is NOT available from the real run, the page
  says so instead of inventing one -- see `approver-attribution`,
  which re-derives at render time whether the human approver's id
  actually reached the SSoT record or the store ledger, rather than
  asserting a claim in prose that would go stale the day it changes.

  ================================================================
  Four of the ten governor checks are unreachable from the shipped advisor
  ================================================================
  `effect-not-propose` / `op-not-allowlisted` / `action-not-allowlisted`
  / `scope-exclusion-violation` are STRUCTURAL: the well-behaved mock
  advisor can never emit a proposal that trips them (this is stated in
  `secsys.sim`'s own ns docstring, and `secsys.governor-self-trip-test`
  proves it). To show them on the page as real holds rather than as
  prose, `run-demo!` injects three deliberately MALFUNCTIONING advisors
  over the SAME store and the SAME compiled graph -- a compromised or
  broken advisor is still just an advisor, and the governor stops it.
  Those runs are labelled with the advisor that produced them in the
  operations table, so a reader can tell which rows came from the
  shipped advisor and which from an injected adversarial one.

  Determinism: no timestamps, no random ids, every collection sorted or
  already ordered by the append-only ledger, so two consecutive runs are
  byte-identical (verify by diffing two runs into two scratch files).

  `-main` REFUSES to write a console whose run produced zero
  `:governor-hold` facts -- a console that shows no real hold is not
  evidence of a governor. Build-time invariant, not a convention.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [secsys.facts :as facts]
            [secsys.governor :as governor]
            [secsys.operation :as op]
            [secsys.phase :as phase]
            [secsys.secsysllm :as secsysllm]
            [secsys.store :as store]))

(def ^:private operator
  {:actor-id "op-1"
   :actor-role :security-systems-operations-coordinator
   :phase 3})

;; ----------------------------- adversarial advisors -----------------------------
;; Each one is a REAL `secsys.secsysllm/Advisor` injected into a REAL
;; `secsys.operation/build` over the SAME store, so the resulting holds
;; are produced by the real governor on a real graph run. See ns docstring.

(def ^:private rogue-actuating-advisor
  "Claims a real-world actuation (`:effect :actuate`) -- something the
  shipped advisor can never emit. Trips HARD check 1 only: its `:action`
  is allowlisted and it cites a real source, so nothing else fires."
  (reify secsysllm/Advisor
    (-advise [_ _ request]
      {:summary    (str (:subject request) ": ROGUE advisor claiming a real-world actuation")
       :rationale  "この助言者は :effect :propose 以外を返す(本来あり得ない)"
       :cites      ["https://www.bsis.ca.gov/"]
       :effect     :actuate
       :action     :site/log
       :value      {:patch {:id (:subject request)}}
       :stake      nil
       :confidence 0.99})))

(def ^:private out-of-scope-action-advisor
  "Proposes an `:action` outside the four-member closed allowlist -- an
  alarm-response dispatch. Trips HARD check 3 only."
  (reify secsysllm/Advisor
    (-advise [_ _ request]
      {:summary    (str (:subject request) ": 助言者が許可されていないactionを提案")
       :rationale  "この助言者は closed allowlist の外の action を返す(本来あり得ない)"
       :cites      ["https://www.bsis.ca.gov/"]
       :effect     :propose
       :action     :alarm/dispatch-response
       :value      {:patch {:id (:subject request)}}
       :stake      nil
       :confidence 0.99})))

(def ^:private scope-exclusion-advisor
  "Keeps a perfectly allowlisted `:action`, but its own prose names a
  finalization act this actor must never perform. Trips HARD check 4
  only -- the second, independent scope layer."
  (reify secsysllm/Advisor
    (-advise [_ _ request]
      {:summary    (str (:subject request) ": 記録更新の提案")
       :rationale  (str "この助言者は allowlist 内の action を保ちながら、"
                        "文言の中で確定行為を名指しする: "
                        "override the access-control system for this site")
       :cites      ["https://www.bsis.ca.gov/"]
       :effect     :propose
       :action     :site/log
       :value      {:patch {:id (:subject request)}}
       :stake      nil
       :confidence 0.99})))

;; ----------------------------- the real run -----------------------------

(defn- record!
  "Append one real graph result to the ordered run log."
  [runs tid advisor-label request result]
  (swap! runs conj {:tid tid
                    :advisor advisor-label
                    :request request
                    :audit (vec (get-in result [:state :audit]))
                    :disposition (get-in result [:state :disposition])
                    :status (:status result)})
  result)

(defn- exec!
  "One operation with no human in the loop (auto-commit or HARD hold)."
  ([runs actor tid request] (exec! runs actor tid "shipped mock advisor" request))
  ([runs actor tid advisor-label request]
   (record! runs tid advisor-label request
            (g/run* actor {:request request :context operator} {:thread-id tid}))))

(defn- resume!
  "One operation the phase gate / governor escalates, then resumed by a
  human decision. `:audit`'s reducer is `into` and the checkpointer
  restores it, so the RESUMED result carries the full accumulated audit
  -- only that one is recorded."
  [runs actor tid request approval]
  (g/run* actor {:request request :context operator} {:thread-id tid})
  (record! runs tid "shipped mock advisor" request
           (g/run* actor {:approval approval} {:thread-id tid :resume? true})))

(defn- approve! [runs actor tid request]
  (resume! runs actor tid request {:status :approved :by "op-1"}))

(defn- reject! [runs actor tid request]
  (resume! runs actor tid request {:status :rejected :by "op-1"}))

(defn run-demo!
  "Drives a fresh seeded store through one full coordination episode plus
  every HARD-hold scenario this actor can reach. Returns
  `{:db :runs}` -- `:db` the real store the actor wrote, `:runs` the
  ordered log of real graph results.

  Episode on `site-1` (USA / BSIS, registered, no permit needed, clean):
  its monitored-system record is logged (the ONLY auto-eligible op at
  phase 3), then an installation-schedule and an equipment-supply
  coordination each escalate to a human and are approved, and each
  double-actuation guard is then exercised in isolation. A
  security-concern flag is approved LAST, which is what makes the
  final `site-1` schedule attempt hold on TWO independent rules at once
  -- an emergent consequence of an earlier real decision in this same
  run, not a scripted row.

  Then the seeded HARD scenarios (`site-6` unknown jurisdiction,
  `site-2` unregistered, `site-3` unconfirmed installation permit,
  `site-4` open security concern, `site-5` pre-existing open supply
  coordination), one human REJECTION on `site-3`, and finally the four
  structural checks driven by the injected adversarial advisors."
  []
  (let [db        (store/seed-db)
        actor     (op/build db)
        ;; same store, same graph shape -- only the advisor is swapped
        rogue     (op/build db {:advisor rogue-actuating-advisor})
        off-scope (op/build db {:advisor out-of-scope-action-advisor})
        off-words (op/build db {:advisor scope-exclusion-advisor})
        runs      (atom [])]

    ;; --- clean coordination episode on site-1 -------------------------------
    (exec! runs actor "t01"
           {:op :log-monitoring-record :subject "site-1"
            :patch {:id "site-1" :client "Riverside Retail Co"}})
    (approve! runs actor "t02"
              {:op :schedule-installation-operation :subject "site-1"})
    ;; double-actuation guard, in isolation (no security concern on file yet)
    (exec! runs actor "t03"
           {:op :schedule-installation-operation :subject "site-1"})
    (approve! runs actor "t04"
              {:op :coordinate-equipment-supply :subject "site-1"})
    (exec! runs actor "t05"
           {:op :coordinate-equipment-supply :subject "site-1"})
    ;; flagging a concern ALWAYS reaches a human; approving it raises the
    ;; concern on site-1, which the next run then hits.
    (approve! runs actor "t06"
              {:op :flag-security-concern :subject "site-1"
               :note "reported repeated false-alarm trips on Zone 3"})
    (exec! runs actor "t07"
           {:op :schedule-installation-operation :subject "site-1"})

    ;; --- seeded HARD scenarios ----------------------------------------------
    (exec! runs actor "t08"
           {:op :log-monitoring-record :subject "site-6"
            :patch {:id "site-6" :client "New Client Co"} :no-spec? true})
    (exec! runs actor "t09"
           {:op :schedule-installation-operation :subject "site-2"})
    ;; the same invariant applies to a NON-highest-stakes op, not only to scheduling
    (exec! runs actor "t10"
           {:op :flag-security-concern :subject "site-2"
            :note "unverified site -- must not be actionable at all"})
    (exec! runs actor "t11"
           {:op :schedule-installation-operation :subject "site-3"})
    (exec! runs actor "t12"
           {:op :schedule-installation-operation :subject "site-4"})
    (exec! runs actor "t13"
           {:op :coordinate-equipment-supply :subject "site-5"})

    ;; --- a human says no ------------------------------------------------------
    (reject! runs actor "t14"
             {:op :flag-security-concern :subject "site-3"
              :note "operator judged this a duplicate of an open ticket"})

    ;; --- the four structural checks, via injected adversarial advisors --------
    (exec! runs rogue "t15" "rogue-actuating-advisor"
           {:op :log-monitoring-record :subject "site-1"
            :patch {:id "site-1"}})
    (exec! runs actor "t16"
           {:op :alarm/dispatch-response :subject "site-1"})
    (exec! runs off-scope "t17" "out-of-scope-action-advisor"
           {:op :log-monitoring-record :subject "site-1"
            :patch {:id "site-1"}})
    (exec! runs off-words "t18" "scope-exclusion-advisor"
           {:op :log-monitoring-record :subject "site-1"
            :patch {:id "site-1"}})

    {:db db :runs @runs}))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [v] (if (keyword? v) (subs (str v) 1) (str v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- muted [v] (str "<span class=\"muted\">" (esc v) "</span>"))

(defn- dash [] "<span class=\"muted\">&mdash;</span>")

(defn- yes-no [v]
  (if (true? v) "<span class=\"ok\">yes</span>" "<span class=\"muted\">no</span>"))

(defn- flag-cell
  "A boolean the governor actually reads, rendered so `true` is visible.
  `warn?` marks the polarity that blocks work."
  [v warn?]
  (cond
    (and (true? v) warn?) "<span class=\"warn\">yes</span>"
    (true? v)             "<span class=\"ok\">yes</span>"
    :else                 "<span class=\"muted\">no</span>"))

(defn- tr [& cells] (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lede (str "    <p class=\"muted\">" lede "</p>\n"))
       body
       "  </section>\n"))

(defn- fact-of [audit t] (first (filter #(= t (:t %)) audit)))

(defn- facts-of [audit t] (filter #(= t (:t %)) audit))

(defn- rules-of [violations] (str/join ", " (map (comp kw-str :rule) violations)))

;; ----------------------------- derived facts -----------------------------

(defn- holds
  "The HARD `:governor-hold` facts the run actually wrote to the ledger.
  A human rejection (`:approval-rejected`) is a HOLD too, but it is not
  a governor hold and is deliberately NOT counted here."
  [db]
  (filterv #(= :governor-hold (:t %)) (store/ledger db)))

(defn- outcome
  "Classify one real run purely from its own accumulated audit trail."
  [{:keys [audit disposition]}]
  (let [hold (last (facts-of audit :governor-hold))
        rej  (fact-of audit :approval-rejected)]
    (cond
      hold {:kind :hard-hold :violations (:violations hold)}
      rej  {:kind :rejected :by (:by (fact-of audit :approval-requested))}
      (fact-of audit :approval-granted)
      {:kind :approved
       :reason (:reason (fact-of audit :approval-requested))
       :by (:by (fact-of audit :approval-granted))}
      (fact-of audit :approval-requested)
      {:kind :awaiting :reason (:reason (fact-of audit :approval-requested))}
      (= :commit disposition) {:kind :auto-commit}
      :else {:kind :other})))

(defn- outcome-cell [o]
  (case (:kind o)
    :hard-hold   (str "<span class=\"critical\">HARD hold &middot; " (esc (rules-of (:violations o))) "</span>")
    :rejected    "<span class=\"warn\">escalated &rarr; human REJECTED &middot; hold</span>"
    :approved    (str "<span class=\"ok\">escalated (" (esc (kw-str (:reason o)))
                      ") &rarr; approved by " (esc (:by o)) "</span>")
    :awaiting    (str "<span class=\"warn\">awaiting human approval &middot; " (esc (kw-str (:reason o))) "</span>")
    :auto-commit "<span class=\"ok\">auto-commit (governor-clean)</span>"
    (muted "in progress")))

(defn- detail-cell [o]
  (case (:kind o)
    :hard-hold (esc (str/join " / " (map :detail (:violations o))))
    :rejected  (muted "approver-rejected -- no SSoT mutation")
    (dash)))

(defn- deep-key-names
  "Every key name appearing anywhere in a nested structure, as strings --
  used to ASK the SSoT what it actually holds instead of assuming."
  [x]
  (cond
    (map? x) (into (into #{} (map #(if (keyword? %) (kw-str %) (str %))) (keys x))
                   (mapcat deep-key-names (vals x)))
    (sequential? x) (into #{} (mapcat deep-key-names x))
    :else #{}))

(def ^:private approver-key-names
  #{"approved-by" "approved_by" "approver" "approver-id" "approved_by_id" "by"})

(defn- approver-attribution
  "DERIVED, at render time, from the real store -- never asserted in prose.

  `secsys.operation`'s `:request-approval` node attaches the approver at
  `[:payload :approved-by]` on the record it hands to `:commit`. Whether
  that survives into the SSoT is a property of THIS repo's
  `store/commit-record!`, and this fleet has five different behaviours
  across sibling repos, so it is measured here rather than assumed:
  every site record and every draft registry record is scanned for an
  approver-shaped key, and the store ledger for an `:approval-granted`
  fact. Returns `{:approvers :on-record? :on-ledger?}` for the page."
  [db runs]
  (let [records (concat (store/all-sites db)
                        (store/schedule-history db)
                        (store/supply-history db))
        names   (deep-key-names records)]
    {:approvers  (vec (sort (into #{} (keep #(:by (fact-of (:audit %) :approval-granted))) runs)))
     :on-record? (boolean (some #(contains? approver-key-names (str/lower-case %)) names))
     :on-ledger? (boolean (some #(= :approval-granted (:t %)) (store/ledger db)))}))

;; ----------------------------- sections -----------------------------

(defn- sites-section [db]
  (section
   "Monitored client sites &mdash; SSoT after this run"
   (str "Every column is read back out of the real <code>secsys.store</code> AFTER the run finished. "
        "The four booleans on the right are the exact facts the governor reads: an installation permit "
        "that is required but unconfirmed, an unresolved security concern, and each double-actuation "
        "guard are all HARD, un-overridable blocks.")
   (table ["Site" "Client" "Address" "Systems" "Jurisdiction" "Registered" "Permit required" "Permit confirmed" "Open concern" "Scheduled" "Supply open" "Schedule no." "Supply no."]
          (for [{:keys [id client address system-types jurisdiction registered?
                        requires-permit? permit-confirmed?
                        security-concern-raised? security-concern-resolved?
                        scheduled? schedule-number
                        supply-coordination-open? supply-coordination-number]} (store/all-sites db)]
            (tr (code id)
                (esc client)
                (esc address)
                (esc (str/join ", " (map kw-str system-types)))
                (code jurisdiction)
                (yes-no registered?)
                (flag-cell requires-permit? false)
                (flag-cell permit-confirmed? false)
                (flag-cell (and (true? security-concern-raised?)
                                (not (true? security-concern-resolved?)))
                           true)
                (flag-cell scheduled? false)
                (flag-cell supply-coordination-open? false)
                (if schedule-number (code schedule-number) (dash))
                (if supply-coordination-number (code supply-coordination-number) (dash)))))))

(defn- runs-section [runs]
  (section
   "Operations this run"
   (str "One row = one real <code>langgraph.graph/run*</code> through the compiled actor "
        "(<code>intake &rarr; advise &rarr; govern &rarr; decide &rarr; commit | hold | request-approval</code>). "
        "The outcome column is classified from each run&rsquo;s own accumulated <code>:audit</code> channel, "
        "not from a literal. Rows whose advisor is not the shipped one were driven by a deliberately "
        "malfunctioning advisor injected over the SAME store to reach the four structural checks "
        "the shipped advisor can never trip.")
   (table ["#" "Op" "Subject" "Advisor" "Outcome" "Governor detail"]
          (for [{:keys [tid request advisor] :as r} runs
                :let [o (outcome r)]]
            (tr (code tid)
                (code (kw-str (:op request)))
                (code (:subject request))
                (if (= advisor "shipped mock advisor")
                  (muted advisor)
                  (str "<span class=\"warn\">" (esc advisor) "</span>"))
                (outcome-cell o)
                (detail-cell o))))))

(defn- holds-section [db]
  (let [hs (holds db)
        by-rule (->> hs
                     (mapcat (fn [h] (map #(vector (:rule %) (:detail %) (:subject h) (:op h)) (:violations h))))
                     (group-by first)
                     (sort-by (comp kw-str key)))]
    (section
     "HARD governor holds &mdash; every rule this run actually tripped"
     (str "Derived from <code>store/ledger</code> filtered to <code>:t :governor-hold</code>; the rule names "
          "and the detail strings are the governor&rsquo;s own <code>:violations</code> entries. "
          "All of these are HARD: they never reach a human, and a human approver cannot override them. "
          "The run wrote <strong>" (count hs) "</strong> governor-hold facts covering <strong>"
          (count by-rule) "</strong> distinct rules.")
     (table ["Rule" "Times tripped" "Ops" "Subjects" "Detail (governor&rsquo;s own text)"]
            (for [[rule entries] by-rule]
              (tr (code (kw-str rule))
                  (str "<span class=\"num\">" (count entries) "</span>")
                  (str/join " " (map code (sort (distinct (map #(kw-str (nth % 3)) entries)))))
                  (str/join " " (map code (sort (distinct (map #(nth % 2) entries)))))
                  (esc (first (distinct (map second entries))))))))))

(defn- phase-section []
  (section
   "Rollout phase gate"
   (str "Rendered directly from <code>secsys.phase/phases</code>. This actor runs at phase "
        (code phase/default-phase) " in this demo. "
        "The <code>auto</code> column is the whole point: only one op is ever auto-eligible, and the "
        "three coordination ops are absent from EVERY phase&rsquo;s auto set &mdash; a permanent "
        "structural fact, not a rollout milestone still to come.")
   (table ["Phase" "Label" "Ops allowed to write" "Ops allowed to auto-commit"]
          (for [[n {:keys [label writes auto]}] (sort-by key phase/phases)]
            (tr (str "<span class=\"num\">" n "</span>"
                     (when (= n phase/default-phase) " <span class=\"badge\">this demo</span>"))
                (esc label)
                (if (seq writes) (str/join " " (map code (sort (map kw-str writes)))) (dash))
                (if (seq auto) (str/join " " (map code (sort (map kw-str auto)))) (dash)))))))

(defn- allowlist-section []
  (section
   "Closed allowlists &mdash; what this actor can even represent"
   (str "Rendered from <code>secsys.governor/allowed-ops</code>, <code>allowed-actions</code>, "
        "<code>high-stakes</code> and <code>scope-exclusion-actions</code>. An alarm-response dispatch "
        "or an access-control override is not merely disallowed by policy &mdash; it cannot be "
        "represented as a member of these sets at all, and a second, independent layer text-scans the "
        "proposal&rsquo;s own prose for the finalization phrases below.")
   (str
    (table ["Set" "Members"]
           [(tr (code "governor/allowed-ops")
                (str/join " " (map code (sort (map kw-str governor/allowed-ops)))))
            (tr (code "governor/allowed-actions")
                (str/join " " (map code (sort (map kw-str governor/allowed-actions)))))
            (tr (code "governor/high-stakes")
                (str/join " " (map code (sort (map kw-str governor/high-stakes)))))
            (tr (code "governor/confidence-floor")
                (str "<span class=\"num\">" governor/confidence-floor "</span>"))])
    "    <p class=\"muted\">Scope-exclusion phrases scanned in every proposal&rsquo;s summary + rationale ("
    (count governor/scope-exclusion-actions) "):</p>\n"
    "    <ul>\n"
    (str/join "\n" (for [p (sort governor/scope-exclusion-actions)]
                     (str "      <li><code>" (esc p) "</code></li>")))
    "\n    </ul>\n")))

(defn- registry-section [db]
  (let [schedules (store/schedule-history db)
        supplies  (store/supply-history db)
        rows (concat
              (for [r schedules]
                (tr (code (get r "record_id")) (esc (get r "kind"))
                    (code (get r "site_id")) (code (get r "jurisdiction"))
                    (yes-no (get r "immutable"))))
              (for [r supplies]
                (tr (code (get r "record_id")) (esc (get r "kind"))
                    (code (get r "site_id")) (code (get r "jurisdiction"))
                    (yes-no (get r "immutable")))))]
    (section
     "Draft coordination records produced"
     (str "<code>store/schedule-history</code> + <code>store/supply-history</code> &mdash; the append-only "
          "draft book-of-record <code>secsys.registry</code> built. Every certificate this actor produces "
          "is <code>status: draft-unsigned</code> with <code>issued_by_registry: false</code>: signing is "
          "the certified operator&rsquo;s act, never this actor&rsquo;s. Only approved commits appear here, "
          "which is why the held runs left nothing behind.")
     (if (seq rows)
       (table ["Record id" "Kind" "Site" "Jurisdiction" "Immutable"] rows)
       "    <p class=\"muted\">No draft record was produced by this run.</p>\n"))))

(defn- approver-section [{:keys [approvers on-record? on-ledger?]}]
  (section
   "Approver attribution &mdash; measured, not asserted"
   (str "This actor escalates three of its four ops to a human at EVERY phase, so &ldquo;who approved "
        "this&rdquo; is a real audit question. Rather than state where the approver ends up (a claim that "
        "would go stale the day the store changes), the generator re-derives it at render time: every "
        "site record and every draft registry record is scanned for an approver-shaped key, and the "
        "store ledger for an <code>:approval-granted</code> fact.")
   (str
    (table ["Question" "Measured answer"]
           [(tr "Approver id(s) on this run&rsquo;s <code>:approval-granted</code> audit facts"
                (if (seq approvers)
                  (str/join " " (map code approvers))
                  (muted "none -- this run produced no human approval")))
            (tr "Approver key present on any committed SSoT record?"
                (if on-record?
                  "<span class=\"ok\">yes</span>"
                  "<span class=\"warn\">no</span>"))
            (tr "<code>:approval-granted</code> present as a store ledger fact?"
                (if on-ledger?
                  "<span class=\"ok\">yes</span>"
                  "<span class=\"warn\">no</span>"))])
    "    <p>"
    (cond
      (empty? approvers)
      "This run produced no human approval, so there is no approver to attribute."

      (and on-record? on-ledger?)
      "The approver is persisted on the committed SSoT record <em>and</em> as a ledger fact."

      on-record?
      (str "The approver is persisted on the committed SSoT record, but not as a separate ledger fact "
           "&mdash; reconstructing it from the ledger alone is not possible.")

      on-ledger?
      (str "The approver is persisted as a ledger fact, but not on the committed SSoT record "
           "&mdash; the record alone does not say who approved it.")

      :else
      (str "<strong>The approver is retained in neither the SSoT record nor the store ledger.</strong> "
           "<code>secsys.operation</code>&rsquo;s <code>:request-approval</code> node attaches it at "
           "<code>[:payload :approved-by]</code>, but <code>store/commit-record!</code> destructures "
           "<code>{:keys [action path value]}</code> and none of its four branches ever reads "
           "<code>:payload</code>; the <code>:commit</code> node then appends only its "
           "<code>:committed</code> fact, never the <code>:approval-granted</code> fact that carries "
           "<code>:by</code>. So the approver ids above are <em>audit only &mdash; not retained in the "
           "store record</em>: they come from this run&rsquo;s in-memory <code>:audit</code> channel and "
           "would be gone on a fresh process. This page states the gap rather than printing an approver "
           "as though the SSoT held one &mdash; silence would make &ldquo;nobody approved&rdquo; and "
           "&ldquo;the store dropped it&rdquo; look identical."))
    "</p>\n")))

(defn- ledger-section [db]
  (let [l (store/ledger db)]
    (section
     "Audit ledger (this run)"
     (str "Append-only decision-fact log, in order &mdash; <strong>" (count l) "</strong> facts. "
          "Every decision, commit or hold, leaves exactly one fact. A hold mutates nothing but the ledger.")
     (table ["#" "Fact" "Op" "Subject" "Actor" "Basis / rules" "Confidence"]
            (map-indexed
             (fn [i {:keys [t op subject actor basis confidence violations]}]
               (tr (str "<span class=\"num\">" (inc i) "</span>")
                   (case t
                     :committed        "<span class=\"ok\">committed</span>"
                     :governor-hold    "<span class=\"critical\">governor-hold</span>"
                     :approval-rejected "<span class=\"warn\">approval-rejected</span>"
                     (esc (kw-str t)))
                   (code (kw-str op))
                   (code subject)
                   (code actor)
                   (cond
                     (seq violations) (esc (rules-of violations))
                     (seq basis)      (esc (str/join ", " (map #(if (keyword? %) (kw-str %) (str %)) basis)))
                     :else            (dash))
                   (if (some? confidence)
                     (str "<span class=\"num\">" confidence "</span>")
                     (dash))))
             l)))))

(defn- jurisdictions-section []
  (let [cov (facts/coverage)]
    (section
     "Jurisdiction spec-basis catalog"
     (str "<code>secsys.facts/catalog</code> &mdash; the table the governor checks every "
          "<code>:log-monitoring-record</code> proposal against. Coverage is reported honestly: "
          "<strong>" (:covered cov) "</strong> of <strong>" (:requested cov) "</strong> requested "
          "jurisdictions have an official spec-basis. A jurisdiction not in this table has NO "
          "spec-basis, full stop &mdash; that is exactly the <code>:no-spec-basis</code> hold above.")
     (table ["ISO3" "Jurisdiction" "Owner authority" "Legal basis" "Provenance" "Required evidence"]
            (for [[iso3 {:keys [name owner-authority legal-basis provenance required-evidence]}]
                  (sort-by key facts/catalog)]
              (tr (code iso3)
                  (esc name)
                  (esc owner-authority)
                  (esc legal-basis)
                  (str "<code>" (esc provenance) "</code>")
                  (str "<span class=\"num\">" (count required-evidence) "</span> items: "
                       (esc (str/join "; " required-evidence)))))))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the full operator-console document from a real `run-demo!`
  result `{:db :runs}`."
  [{:keys [db runs]}]
  (let [hs  (holds db)
        att (approver-attribution db runs)]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-8020 &middot; security systems service activities &mdash; Operator Console</title>\n"
     "<style>" (jp-go-dds.skin/dds+skin) "</style>\n"
     "</head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Security systems service activities (ISIC 8020) &mdash; Operator Console</h1>\n"
     "</header>\n"
     "<p class=\"subtitle\"><span class=\"badge\">read-only sample</span> "
     "<span class=\"badge\">governor-gated</span> "
     "<span class=\"badge\">installation scheduling / concern flagging / equipment supply always human-approved</span></p>\n"
     "<div class=\"banner\">\n"
     "  <p>This page is <strong>generated at build time by running the real actor</strong> "
     "(<code>clojure -M:dev:render-html</code> &rarr; <code>secsys.render-html</code>), not written by hand. "
     "Every row below traces to seeded store data or to post-run store / audit state: "
     "<strong>" (count runs) "</strong> real graph runs, "
     "<strong>" (count (store/ledger db)) "</strong> ledger facts, "
     "<strong>" (count hs) "</strong> HARD governor holds. "
     "The generator refuses to write this file at all if the run produces zero governor holds &mdash; "
     "a console that shows no real hold is not evidence of a governor.</p>\n"
     "  <p class=\"muted\">This actor coordinates security-systems installation, monitoring and maintenance "
     "scheduling only. It is never the alarm-response dispatcher and never the access-control-override "
     "authority: those acts cannot be represented in its closed op/action allowlists at all, and a second, "
     "independent layer scans every proposal&rsquo;s own prose for them.</p>\n"
     "</div>\n"
     "<main>\n"
     (sites-section db)
     (runs-section runs)
     (holds-section db)
     (phase-section)
     (allowlist-section)
     (registry-section db)
     (approver-section att)
     (ledger-section db)
     (jurisdictions-section)
     "</main>\n"
     "<footer>\n"
     "  <p>Regenerate with <code>clojure -M:dev:render-html [out-file]</code> from the repo root. "
     "The output is deterministic: no timestamps, no random ids, every collection either sorted or "
     "already ordered by the append-only ledger, so two consecutive runs are byte-identical.</p>\n"
     "  <p class=\"muted\">cloud-itonami-isic-8020 &middot; source: <code>src/secsys/render_html.clj</code> "
     "&middot; actor: <code>secsys.operation</code> &middot; censor: <code>secsys.governor</code> "
     "&middot; SSoT: <code>secsys.store</code></p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        db     (:db result)
        hs     (holds db)]
    ;; Build-time invariant: a console that shows no real HARD hold is not
    ;; evidence of a governor. Refuse to write one.
    (when (empty? hs)
      (throw (ex-info (str "no :governor-hold fact on the ledger -- refusing to write a console "
                           "that shows no real hold")
                      {:ledger-facts (count (store/ledger db))
                       :runs (count (:runs result))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (:runs result)) " real graph runs, "
                  (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD governor holds covering "
                  (count (distinct (mapcat #(map :rule (:violations %)) hs))) " distinct rules, "
                  (count (store/schedule-history db)) " schedule drafts, "
                  (count (store/supply-history db)) " supply drafts)"))))
