(ns secsys.registry
  "Pure-function installation-schedule + equipment-supply-coordination
  record construction -- an append-only security-systems-operations
  coordination draft book-of-record.

  Like every sibling actor's registry, there is no single
  international reference-number standard for an installation-
  schedule or an equipment-supply-coordination record -- every
  security-systems operator/jurisdiction assigns its own reference
  format. This namespace does NOT invent one; it builds a
  jurisdiction-scoped sequence number and validates the record's
  required fields, the same honest, non-fabricating discipline
  `secsys.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real monitoring-center, dispatch, or access-control
  system. It builds the COORDINATION RECORD this actor would keep, not
  a real alarm-response dispatch or a real access-control override --
  both of those remain a certified emergency-services/security-
  authority's own act, entirely outside this actor's closed op
  allowlist (see `secsys.governor` ns docstring `SCOPE`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the certified operator's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-installation-schedule
  "Validate + construct the INSTALLATION-SCHEDULE registration DRAFT --
  a scheduling COORDINATION note, never a real dispatch/actuation. Pure
  function -- does not touch any real dispatch/monitoring-center
  system; it builds the RECORD this actor would keep.
  `secsys.governor` independently re-verifies the site's own
  registration/permit/security-concern ground truth, and blocks a
  double-schedule of the same site, before this is ever allowed to
  commit."
  [site-id jurisdiction sequence]
  (when-not (and site-id (not= site-id ""))
    (throw (ex-info "installation-schedule: site_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "installation-schedule: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "installation-schedule: sequence must be >= 0" {})))
  (let [schedule-number (str (str/upper-case jurisdiction) "-INST-" (zero-pad sequence 6))
        record {"record_id" schedule-number
                "kind" "installation-schedule-draft"
                "site_id" site-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "schedule_number" schedule-number
     "certificate" (unsigned-certificate "InstallationSchedule" schedule-number schedule-number)}))

(defn register-supply-coordination
  "Validate + construct the EQUIPMENT-SUPPLY-COORDINATION registration
  DRAFT -- a procurement coordination note, never a real purchase
  order or shipment release. Pure function -- does not touch any real
  procurement/inventory system; it builds the RECORD this actor would
  keep. `secsys.governor` independently re-verifies the site's own
  registration/security-concern ground truth, and blocks opening a
  second coordination request while one is already open, before this
  is ever allowed to commit."
  [site-id jurisdiction sequence]
  (when-not (and site-id (not= site-id ""))
    (throw (ex-info "supply-coordination: site_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "supply-coordination: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "supply-coordination: sequence must be >= 0" {})))
  (let [supply-number (str (str/upper-case jurisdiction) "-SUP-" (zero-pad sequence 6))
        record {"record_id" supply-number
                "kind" "equipment-supply-coordination-draft"
                "site_id" site-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "supply_coordination_number" supply-number
     "certificate" (unsigned-certificate "SupplyCoordination" supply-number supply-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
