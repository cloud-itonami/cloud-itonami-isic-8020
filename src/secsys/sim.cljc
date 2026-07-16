(ns secsys.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean site through
  record logging -> installation-schedule coordination (escalate/
  approve/commit) -> equipment-supply coordination (escalate/approve/
  commit) -> security-concern flag (always escalate/approve/commit),
  then shows HARD-hold scenarios: a jurisdiction with no spec-basis, an
  unregistered client-site record, an unconfirmed installation permit,
  an open security concern, an already-open equipment-supply
  coordination, a double-schedule, and a double supply-coordination.

  Like every sibling actor's new checks, this actor's new checks
  (`record-not-verified?`, `installation-permit-unconfirmed?`,
  `open-security-concern?`) are evaluated directly at `:schedule-
  installation-operation`/`:coordinate-equipment-supply` time rather
  than via a separate screening op -- a real scheduling/coordination
  decision validates a registered record, a confirmed installation
  permit and a clear security-concern status at the point of the
  proposal itself. Each check is still exercised directly and
  independently below, one site per HARD-hold scenario, following the
  SAME 'exercise the failure mode directly, never only via a happy-
  path actuation' discipline every sibling since `parksafety`'s
  ADR-2607071922 Decision 5 establishes. The purely structural checks
  (`effect-not-propose`/`op-not-allowlisted`/`action-not-allowlisted`/
  `scope-exclusion-violation`) are never reachable via this well-
  behaved mock advisor's own output -- they are exercised directly in
  `test/secsys/governor_contract_test.clj` against a hand-crafted
  adversarial proposal instead."
  (:require [langgraph.graph :as g]
            [secsys.store :as store]
            [secsys.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :security-systems-operations-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== log-monitoring-record site-1 (USA, clean) ==")
    (println (exec-op actor "t1" {:op :log-monitoring-record :subject "site-1"
                                  :patch {:id "site-1" :client "Riverside Retail Co"}} operator))

    (println "== schedule-installation-operation site-1 (always escalates -- security/schedule-installation) ==")
    (let [r (exec-op actor "t2" {:op :schedule-installation-operation :subject "site-1"} operator)]
      (println r)
      (println "-- human security-systems operations coordinator approves --")
      (println (approve! actor "t2")))

    (println "== coordinate-equipment-supply site-1 (always escalates -- security/coordinate-supply) ==")
    (let [r (exec-op actor "t3" {:op :coordinate-equipment-supply :subject "site-1"} operator)]
      (println r)
      (println "-- human security-systems operations coordinator approves --")
      (println (approve! actor "t3")))

    (println "== flag-security-concern site-1 (always escalates -- security/flag-concern) ==")
    (let [r (exec-op actor "t4" {:op :flag-security-concern :subject "site-1"
                                 :note "reported repeated false-alarm trips on Zone 3"} operator)]
      (println r)
      (println "-- human security-systems operations coordinator approves --")
      (println (approve! actor "t4")))

    (println "== log-monitoring-record site-6 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :log-monitoring-record :subject "site-6"
                                  :patch {:id "site-6" :client "New Client Co"} :no-spec? true} operator))

    (println "== schedule-installation-operation site-2 (record never verified/registered -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :schedule-installation-operation :subject "site-2"} operator))

    (println "== schedule-installation-operation site-3 (permit-requiring, unconfirmed -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :schedule-installation-operation :subject "site-3"} operator))

    (println "== schedule-installation-operation site-4 (open security concern -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :schedule-installation-operation :subject "site-4"} operator))

    (println "== coordinate-equipment-supply site-5 (already an open supply coordination -> HARD hold) ==")
    (println (exec-op actor "t9" {:op :coordinate-equipment-supply :subject "site-5"} operator))

    (println "== schedule-installation-operation site-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t10" {:op :schedule-installation-operation :subject "site-1"} operator))

    (println "== coordinate-equipment-supply site-1 AGAIN (double-coordinate -> HARD hold) ==")
    (println (exec-op actor "t11" {:op :coordinate-equipment-supply :subject "site-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft installation-schedule records ==")
    (doseq [r (store/schedule-history db)] (println r))

    (println "== draft equipment-supply-coordination records ==")
    (doseq [r (store/supply-history db)] (println r))))
