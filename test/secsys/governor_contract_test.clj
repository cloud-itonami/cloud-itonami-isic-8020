(ns secsys.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  invariant under test:

    SecuritySystems-LLM never proposes an installation-schedule/
    equipment-supply-coordination the Security Systems Governor would
    reject, `:schedule-installation-operation`/`:flag-security-
    concern`/`:coordinate-equipment-supply` NEVER auto-commit at any
    phase, `:log-monitoring-record` (no dispatch/operational risk) MAY
    auto-commit when clean, and every decision (commit OR hold) leaves
    exactly one ledger fact. Also covers the STRUCTURAL checks
    (`effect-not-propose`/`op-not-allowlisted`/`action-not-
    allowlisted`/`scope-exclusion-violation`) directly against hand-
    crafted adversarial proposals, since the well-behaved mock advisor
    never reaches them on its own (see `secsys.sim` ns docstring)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [secsys.governor :as governor]
            [secsys.secsysllm :as secsysllm]
            [secsys.store :as store]
            [secsys.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :security-systems-operations-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-monitoring-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-monitoring-record :subject "site-1"
                   :patch {:id "site-1" :client "Updated Retail Co"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Updated Retail Co" (:client (store/site db "site-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-installation-operation-always-needs-approval
  (testing "schedule is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :schedule-installation-operation :subject "site-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:scheduled? (store/site db "site-1"))))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a log-monitoring-record proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-monitoring-record :subject "site-6"
                     :patch {:id "site-6"} :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/site db "site-6"))
          "no record created -- HOLD never merged the unverified patch"))))

(deftest schedule-without-registered-record-is-held
  (testing "schedule-installation-operation before any :log-monitoring-record commit -> HOLD (record-not-verified)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :schedule-installation-operation :subject "site-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:record-not-verified} (-> (store/ledger db) first :basis))))))

(deftest flag-security-concern-without-registered-record-is-held
  (testing "flag-security-concern before any :log-monitoring-record commit -> HOLD (record-not-verified) -- 'before ANY action', not only the highest-stakes op"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :flag-security-concern :subject "site-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:record-not-verified} (-> (store/ledger db) first :basis))))))

(deftest installation-permit-unconfirmed-is-held-and-unoverridable
  (testing "a permit-requiring site with no confirmed permit/compliance check -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :schedule-installation-operation :subject "site-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:installation-permit-unconfirmed} (-> (store/ledger db) last :basis)))
      (is (empty? (store/schedule-history db))))))

(deftest open-security-concern-is-held-and-unoverridable-on-schedule
  (testing "an unresolved security concern -> HOLD on schedule-installation-operation, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :schedule-installation-operation :subject "site-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:open-security-concern} (-> (store/ledger db) last :basis)))
      (is (empty? (store/schedule-history db))))))

(deftest open-security-concern-is-held-and-unoverridable-on-coordinate-supply
  (testing "an unresolved security concern -> HOLD on coordinate-equipment-supply too, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t8" {:op :coordinate-equipment-supply :subject "site-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:open-security-concern} (-> (store/ledger db) last :basis)))
      (is (empty? (store/supply-history db))))))

(deftest coordinate-supply-already-open-is-held-and-unoverridable
  (testing "a site with an already-open equipment-supply coordination -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t9" {:op :coordinate-equipment-supply :subject "site-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:already-coordinating} (-> (store/ledger db) last :basis)))
      (is (= 0 (count (store/supply-history db)))
          "no NEW draft record -- the pre-existing open one is untouched by this actor"))))

(deftest schedule-installation-operation-always-escalates-then-human-decides
  (testing "a clean, fully-registered, permit-clear, no-concern site still ALWAYS interrupts for human approval -- security/schedule-installation is never auto"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t10" {:op :schedule-installation-operation :subject "site-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, installation-schedule record drafted"
        (let [r2 (approve! actor "t10")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:scheduled? (store/site db "site-1"))))
          (is (= 1 (count (store/schedule-history db))) "one draft installation-schedule record"))))))

(deftest coordinate-equipment-supply-always-escalates-then-human-decides
  (testing "a clean, fully-registered, no-concern, not-already-open site still ALWAYS interrupts for human approval -- security/coordinate-supply is never auto"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t11" {:op :coordinate-equipment-supply :subject "site-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, equipment-supply-coordination record drafted"
        (let [r2 (approve! actor "t11")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:supply-coordination-open? (store/site db "site-1"))))
          (is (= 1 (count (store/supply-history db)))))))))

(deftest flag-security-concern-always-escalates-then-human-decides
  (testing "flag-security-concern ALWAYS interrupts for human approval, even for a clean/registered site -- it is the highest-caution op in this domain"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t12" {:op :flag-security-concern :subject "site-1"
                                   :note "unusual repeated door-contact trips reported"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, concern recorded on the site"
        (let [r2 (approve! actor "t12")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:security-concern-raised? (store/site db "site-1"))))
          (is (false? (:security-concern-resolved? (store/site db "site-1")))))))))

(deftest schedule-installation-operation-double-schedule-is-held
  (testing "scheduling the same site twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t13a" {:op :schedule-installation-operation :subject "site-1"} operator)
          _ (approve! actor "t13a")
          res (exec-op actor "t13" {:op :schedule-installation-operation :subject "site-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/schedule-history db))) "still only the one earlier schedule"))))

(deftest coordinate-equipment-supply-double-coordination-is-held
  (testing "opening a second equipment-supply-coordination request for the same site -> HOLD"
    (let [[db actor] (fresh)
          _ (exec-op actor "t14a" {:op :coordinate-equipment-supply :subject "site-1"} operator)
          _ (approve! actor "t14a")
          res (exec-op actor "t14" {:op :coordinate-equipment-supply :subject "site-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-coordinating} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/supply-history db))) "still only the one earlier coordination"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-monitoring-record :subject "site-1"
                          :patch {:id "site-1" :client "Riverside Retail Co"}} operator)
      (exec-op actor "b" {:op :log-monitoring-record :subject "site-6"
                          :patch {:id "site-6"} :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

;; ----------------------- structural checks (hand-crafted proposals) -----------------------

(deftest effect-not-propose-is-a-hard-permanent-block
  (testing "a proposal that does not carry the literal :effect :propose is hard-blocked, unconditionally"
    (let [[db _actor] (fresh)
          bad {:summary "s" :rationale "r" :cites [] :effect :execute
               :action :site/mark-scheduled :value {:site-id "site-1"}
               :stake :security/schedule-installation :confidence 0.99}
          verdict (governor/check {:op :schedule-installation-operation :subject "site-1"} operator bad db)]
      (is (:hard? verdict))
      (is (some #{:effect-not-propose} (mapv :rule (:violations verdict))))
      (is (not (:ok? verdict))))))

(deftest op-not-in-allowlist-is-a-hard-permanent-block
  (testing "an op outside the four-member closed allowlist is hard-blocked, unconditionally -- e.g. a hypothetical direct-alarm-response-dispatch op can never even be represented"
    (let [[db _actor] (fresh)
          proposal {:summary "s" :rationale "r" :cites [] :effect :propose
                     :action :site/mark-scheduled :value {} :stake nil :confidence 0.99}
          verdict (governor/check {:op :dispatch/respond-to-alarm :subject "site-1"} operator proposal db)]
      (is (:hard? verdict))
      (is (some #{:op-not-allowlisted} (mapv :rule (:violations verdict)))))))

(deftest action-not-in-allowlist-is-a-hard-permanent-block
  (testing "an :action outside the four-member closed allowlist is hard-blocked, unconditionally -- structurally excludes any alarm-response-dispatch or access-control-override action"
    (let [[db _actor] (fresh)
          proposal {:summary "s" :rationale "r" :cites [] :effect :propose
                     :action :alarm/dispatch-response :value {} :stake nil :confidence 0.99}
          verdict (governor/check {:op :schedule-installation-operation :subject "site-1"} operator proposal db)]
      (is (:hard? verdict))
      (is (some #{:action-not-allowlisted} (mapv :rule (:violations verdict)))))))

(deftest scope-exclusion-phrase-in-rationale-is-a-hard-permanent-block
  (testing "a proposal whose OWN rationale/summary names a forbidden finalization action is hard-blocked, unconditionally, even with a well-formed :action and high confidence -- and a human approver could never override it (HOLD never reaches :request-approval)"
    (let [[db _actor] (fresh)
          bad-advisor (reify secsysllm/Advisor
                        (-advise [_ _st _req]
                          {:summary "site-1 向け設置スケジュール調整案"
                           :rationale "dispatch police response for this alarm regardless of verification status"
                           :cites ["site-1"] :effect :propose
                           :action :site/mark-scheduled :value {:site-id "site-1"}
                           :stake :security/schedule-installation :confidence 0.99}))
          actor2 (op/build db {:advisor bad-advisor})
          res (exec-op actor2 "tbad" {:op :schedule-installation-operation :subject "site-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)) "never reaches request-approval -- unoverridable")
      (is (some #{:scope-exclusion-violation} (-> (store/ledger db) last :basis))))))
