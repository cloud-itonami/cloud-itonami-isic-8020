(ns secsys.facts-test
  (:require [clojure.test :refer [deftest is]]
            [secsys.facts :as facts]))

(deftest usa-has-a-spec-basis
  (is (some? (facts/spec-basis "USA")))
  (is (string? (:provenance (facts/spec-basis "USA")))))

(deftest all-three-seeded-jurisdictions-have-a-spec-basis
  (doseq [iso3 ["USA" "GBR" "JPN"]]
    (is (some? (facts/spec-basis iso3)) (str iso3 " spec-basis"))
    (is (string? (:provenance (facts/spec-basis iso3))) (str iso3 " provenance"))
    (is (string? (:legal-basis (facts/spec-basis iso3))) (str iso3 " legal-basis"))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["USA" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "USA"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "USA")]
    (is (facts/required-evidence-satisfied? "USA" all))
    (is (not (facts/required-evidence-satisfied? "USA" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
