(ns secsys.phase
  "Phase 0->3 staged rollout for the community-security-systems-
  service-operations-coordination actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-logging   -- client-site/monitored-system record
                                    logging allowed, every write needs
                                    human approval.
    Phase 2  assisted-coord     -- adds installation-scheduling /
                                    security-concern flagging /
                                    equipment-supply coordination
                                    writes, still approval.
    Phase 3  supervised auto    -- governor-clean, high-confidence
                                    `:log-monitoring-record` (no
                                    dispatch/operational weight, pure
                                    data logging) may auto-commit.
                                    `:schedule-installation-operation`/
                                    `:flag-security-concern`/
                                    `:coordinate-equipment-supply`
                                    NEVER auto-commit, at any phase.

  `:schedule-installation-operation`/`:flag-security-concern`/
  `:coordinate-equipment-supply` are deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. This actor coordinates
  security-systems installation/monitoring/maintenance scheduling
  only; it is never the alarm-response dispatcher or the access-
  control-override authority (see `secsys.governor` ns docstring
  `SCOPE`), and flagging a security concern in particular must ALWAYS
  reach a human -- `secsys.governor`'s own `high-stakes` gate enforces
  the same invariant independently for all three ops. Two layers, not
  one, agree on this. Like every prior sibling's own real-actuation
  phase-3 `:auto` set, this domain has only ONE member
  (`:log-monitoring-record`) -- no separate no-operational-risk 'file'
  lifecycle distinct from the site itself.")

(def read-ops  #{})
(def write-ops #{:log-monitoring-record :schedule-installation-operation
                  :flag-security-concern :coordinate-equipment-supply})

;; NOTE the invariant: `:schedule-installation-operation`/`:flag-
;; security-concern`/`:coordinate-equipment-supply` are members of
;; `write-ops` (governor-gated like any write) but are NEVER members
;; of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"           :writes #{}                                     :auto #{}}
   1 {:label "assisted-logging"    :writes #{:log-monitoring-record}               :auto #{}}
   2 {:label "assisted-coordination" :writes write-ops                             :auto #{}}
   3 {:label "supervised-auto"     :writes write-ops
      :auto #{:log-monitoring-record}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:schedule-installation-operation`/`:flag-security-concern`/
    `:coordinate-equipment-supply` are never auto-eligible at any
    phase, so they always escalate once the governor clears them (or
    hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Security Systems Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
