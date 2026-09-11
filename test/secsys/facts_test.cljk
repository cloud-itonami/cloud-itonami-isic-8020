(ns secsys.facts-test
  (:require [clojure.test :refer [deftest is]]
            [secsys.facts :as facts]))

(deftest usa-has-a-spec-basis
  (is (some? (facts/spec-basis "USA")))
  (is (string? (:provenance (facts/spec-basis "USA")))))

(deftest all-four-seeded-jurisdictions-have-a-spec-basis
  (doseq [iso3 ["USA" "GBR" "JPN" "AUS"]]
    (is (some? (facts/spec-basis iso3)) (str iso3 " spec-basis"))
    (is (string? (:provenance (facts/spec-basis iso3))) (str iso3 " provenance"))
    (is (string? (:legal-basis (facts/spec-basis iso3))) (str iso3 " legal-basis"))))

(deftest aus-is-nsw-specifically-with-a-cited-licence-class
  (let [aus (facts/spec-basis "AUS")]
    (is (some? aus))
    (is (= "Australia (New South Wales)" (:name aus))
        "AUS entry must disclose it models NSW, not a national figure")
    (is (re-find #"(?i)Security Licensing and Enforcement Directorate" (:owner-authority aus)))
    (is (re-find #"Security Industry Act 1997" (:legal-basis aus)))
    (is (re-find #"class 2C" (:legal-basis aus))
        "legal-basis must cite the specific licence subclass, not just the Act name")
    (is (string? (:provenance aus)))
    (is (= 4 (count (:required-evidence aus)))
        "AUS required-evidence must have the same shape as USA/GBR/JPN (4 items)")))

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
