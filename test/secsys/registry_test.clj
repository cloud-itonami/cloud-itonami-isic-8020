(ns secsys.registry-test
  (:require [clojure.test :refer [deftest is]]
            [secsys.registry :as r]))

;; ----------------------------- register-installation-schedule -----------------------------

(deftest schedule-is-a-draft-not-a-real-dispatch
  (let [result (r/register-installation-schedule "site-1" "USA" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest schedule-assigns-schedule-number
  (let [result (r/register-installation-schedule "site-1" "USA" 7)]
    (is (= (get result "schedule_number") "USA-INST-000007"))
    (is (= (get-in result ["record" "site_id"]) "site-1"))
    (is (= (get-in result ["record" "kind"]) "installation-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest schedule-validation-rules
  (is (thrown? Exception (r/register-installation-schedule "" "USA" 0)))
  (is (thrown? Exception (r/register-installation-schedule "site-1" "" 0)))
  (is (thrown? Exception (r/register-installation-schedule "site-1" "USA" -1))))

;; ----------------------------- register-supply-coordination -----------------------------

(deftest supply-coordination-is-a-draft-not-a-real-purchase
  (let [result (r/register-supply-coordination "site-1" "USA" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest supply-coordination-assigns-supply-number
  (let [result (r/register-supply-coordination "site-1" "USA" 7)]
    (is (= (get result "supply_coordination_number") "USA-SUP-000007"))
    (is (= (get-in result ["record" "site_id"]) "site-1"))
    (is (= (get-in result ["record" "kind"]) "equipment-supply-coordination-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest supply-coordination-validation-rules
  (is (thrown? Exception (r/register-supply-coordination "" "USA" 0)))
  (is (thrown? Exception (r/register-supply-coordination "site-1" "" 0)))
  (is (thrown? Exception (r/register-supply-coordination "site-1" "USA" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-installation-schedule "site-1" "USA" 0)
        hist (r/append [] c1)
        c2 (r/register-installation-schedule "site-2" "USA" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "USA-INST-000000" (get-in hist2 [0 "record_id"])))
    (is (= "USA-INST-000001" (get-in hist2 [1 "record_id"])))))
