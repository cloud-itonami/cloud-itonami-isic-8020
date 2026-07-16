(ns secsys.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:schedule-installation-operation`/`:flag-security-
  concern`/`:coordinate-equipment-supply` must NEVER be a member of
  any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [secsys.phase :as phase]))

(deftest schedule-installation-operation-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real installation schedule"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :schedule-installation-operation))
          (str "phase " n " must not auto-commit :schedule-installation-operation")))))

(deftest flag-security-concern-never-auto-at-any-phase
  (testing "structural invariant: flagging a concern must ALWAYS reach a human -- never in any phase's :auto set"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-security-concern))
          (str "phase " n " must not auto-commit :flag-security-concern")))))

(deftest coordinate-equipment-supply-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real equipment-supply coordination"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :coordinate-equipment-supply))
          (str "phase " n " must not auto-commit :coordinate-equipment-supply")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-operational-risk-ops
  (testing ":log-monitoring-record carries no direct dispatch/operational risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-monitoring-record} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-monitoring-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-installation-operation} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-security-concern} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :coordinate-equipment-supply} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-monitoring-record} :commit)))))
