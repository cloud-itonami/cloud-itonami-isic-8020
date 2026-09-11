(ns secsys.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-4912`'s
  own `railfreight.store-contract-test` for the same pattern on a
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [secsys.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "USA" (:jurisdiction (store/site s "site-1"))))
      (is (true? (:registered? (store/site s "site-1"))))
      (is (false? (:registered? (store/site s "site-2"))))
      (is (true? (:requires-permit? (store/site s "site-3"))))
      (is (false? (:permit-confirmed? (store/site s "site-3"))))
      (is (true? (:security-concern-raised? (store/site s "site-4"))))
      (is (false? (:security-concern-resolved? (store/site s "site-4"))))
      (is (true? (:supply-coordination-open? (store/site s "site-5"))))
      (is (false? (:scheduled? (store/site s "site-1"))))
      (is (= ["site-1" "site-2" "site-3" "site-4" "site-5"]
             (mapv :id (store/all-sites s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/schedule-history s)))
      (is (= [] (store/supply-history s))
          "the pre-seeded site-5 flag is not itself a committed-history entry")
      (is (zero? (store/next-schedule-sequence s "USA")))
      (is (zero? (store/next-supply-sequence s "USA")))
      (is (false? (store/site-already-scheduled? s "site-1")))
      (is (true? (store/site-supply-already-open? s "site-5"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:action :site/log :path ["site-1"]
                                 :value {:patch {:id "site-1" :client "Updated Retail Co"}
                                        :spec-basis "https://example.test/spec" :legal-basis "Test Act"}})
        (is (= "Updated Retail Co" (:client (store/site s "site-1"))))
        (is (= "100 Riverside Ave" (:address (store/site s "site-1"))) "unrelated field preserved")
        (is (true? (:registered? (store/site s "site-1")))))
      (testing "security-concern flag commits"
        (store/commit-record! s {:action :site/flag-security-concern :path ["site-1"]
                                 :value {:note "reported tamper alert on panel"}})
        (is (true? (:security-concern-raised? (store/site s "site-1"))))
        (is (false? (:security-concern-resolved? (store/site s "site-1")))))
      (testing "installation schedule drafts a record and advances the schedule sequence"
        (store/commit-record! s {:action :site/mark-scheduled :path ["site-1"]})
        (is (= "USA-INST-000000" (get (first (store/schedule-history s)) "record_id")))
        (is (= "installation-schedule-draft" (get (first (store/schedule-history s)) "kind")))
        (is (true? (:scheduled? (store/site s "site-1"))))
        (is (= 1 (count (store/schedule-history s))))
        (is (= 1 (store/next-schedule-sequence s "USA")))
        (is (true? (store/site-already-scheduled? s "site-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/site s "nope")))
    (is (= [] (store/all-sites s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/schedule-history s)))
    (is (= [] (store/supply-history s)))
    (is (zero? (store/next-schedule-sequence s "USA")))
    (is (zero? (store/next-supply-sequence s "USA")))
    (store/with-sites s {"x" {:id "x" :client "c" :address "a"
                              :system-types [:alarm] :requires-permit? false
                              :registered? true :spec-basis "https://example.test/spec" :legal-basis "Test Act"
                              :permit-confirmed? false
                              :security-concern-raised? false :security-concern-resolved? false
                              :scheduled? false :schedule-number nil
                              :supply-coordination-open? false :supply-coordination-number nil
                              :jurisdiction "USA" :status :intake}})
    (is (= "c" (:client (store/site s "x"))))))
