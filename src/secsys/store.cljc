(ns secsys.store
  "SSoT for the community-security-systems-service-operations-
  coordination actor, behind a `Store` protocol so the backend is a
  swap, not a rewrite -- the same seam every prior `cloud-itonami-
  isic-*` actor in this fleet uses (see e.g. `cloud-itonami-isic-4912`'s
  own `railfreight.store`).

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/secsys/store_contract_test.clj), which is the whole point: the
  actor, the Security Systems Governor and the audit ledger never know
  which SSoT they run on.

  The single entity here is a `site` (a client-site / monitored
  alarm-CCTV-access-control-system record under coordination).
  `:log-monitoring-record` registers/updates it, `:schedule-
  installation-operation` and `:coordinate-equipment-supply` each
  apply SEQUENTIALLY to the SAME site (a site can be scheduled once
  its record is verified/registered, and separately have equipment
  supply coordinated), with dedicated double-actuation-guard booleans
  (`:scheduled?`/`:supply-coordination-open?`, never a `:status`
  value) -- the same discipline every sibling actor's own guard
  booleans use.

  The ledger stays append-only on every backend: 'which site was
  screened for an unregistered record, an unconfirmed installation
  permit, or an open security concern, which site was scheduled for
  installation, which equipment-supply coordination was opened, on
  what jurisdictional basis, approved by whom' is always a query over
  an immutable log -- the audit trail a client or regulator trusting a
  security-systems operator needs, and the evidence an operator needs
  if a schedule or a supply-coordination action is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [secsys.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (site [s id])
  (all-sites [s])
  (ledger [s])
  (schedule-history [s] "the append-only installation-schedule history (secsys.registry drafts)")
  (supply-history [s] "the append-only equipment-supply-coordination history (secsys.registry drafts)")
  (next-schedule-sequence [s jurisdiction] "next schedule-number sequence for a jurisdiction")
  (next-supply-sequence [s jurisdiction] "next supply-coordination-number sequence for a jurisdiction")
  (site-already-scheduled? [s site-id] "has this site's installation operation already been scheduled?")
  (site-supply-already-open? [s site-id] "does this site already have an open equipment-supply-coordination request?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-sites [s sites] "replace/seed the site directory (map id->site)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained site set covering both coordination
  lifecycles (schedule, equipment supply) plus the governor's own
  checks, so the actor + tests run offline."
  []
  {:sites
   {"site-1" {:id "site-1" :client "Riverside Retail Co"
               :address "100 Riverside Ave" :system-types [:alarm :cctv]
               :requires-permit? false
               :registered? true :spec-basis "https://www.bsis.ca.gov/"
               :legal-basis "California Business and Professions Code, Chapter 11.6 (Alarm Company Act)"
               :permit-confirmed? false
               :security-concern-raised? false :security-concern-resolved? false
               :scheduled? false :schedule-number nil
               :supply-coordination-open? false :supply-coordination-number nil
               :jurisdiction "USA" :status :intake}
    "site-2" {:id "site-2" :client "Riverside Retail Co"
               :address "200 Riverside Ave" :system-types [:cctv]
               :requires-permit? false
               :registered? false :spec-basis nil :legal-basis nil
               :permit-confirmed? false
               :security-concern-raised? false :security-concern-resolved? false
               :scheduled? false :schedule-number nil
               :supply-coordination-open? false :supply-coordination-number nil
               :jurisdiction "USA" :status :intake}
    "site-3" {:id "site-3" :client "Harborview Logistics"
               :address "12 Harbor Rd" :system-types [:alarm :access-control]
               :requires-permit? true
               :registered? true :spec-basis "https://www.bsis.ca.gov/"
               :legal-basis "California Business and Professions Code, Chapter 11.6 (Alarm Company Act)"
               :permit-confirmed? false
               :security-concern-raised? false :security-concern-resolved? false
               :scheduled? false :schedule-number nil
               :supply-coordination-open? false :supply-coordination-number nil
               :jurisdiction "USA" :status :intake}
    "site-4" {:id "site-4" :client "Harborview Logistics"
               :address "14 Harbor Rd" :system-types [:alarm]
               :requires-permit? false
               :registered? true :spec-basis "https://www.bsis.ca.gov/"
               :legal-basis "California Business and Professions Code, Chapter 11.6 (Alarm Company Act)"
               :permit-confirmed? false
               :security-concern-raised? true :security-concern-resolved? false
               :scheduled? false :schedule-number nil
               :supply-coordination-open? false :supply-coordination-number nil
               :jurisdiction "USA" :status :intake}
    "site-5" {:id "site-5" :client "Harborview Logistics"
               :address "16 Harbor Rd" :system-types [:cctv]
               :requires-permit? false
               :registered? true :spec-basis "https://www.bsis.ca.gov/"
               :legal-basis "California Business and Professions Code, Chapter 11.6 (Alarm Company Act)"
               :permit-confirmed? false
               :security-concern-raised? false :security-concern-resolved? false
               :scheduled? false :schedule-number nil
               ;; already has an open equipment-supply-coordination request --
               ;; represents one opened through some earlier session/tool
               ;; (its own number is out of THIS store's own sequence
               ;; counter and history, deliberately: the flag alone is
               ;; what `already-coordinating-violations` checks, never a
               ;; history-vector length).
               :supply-coordination-open? true :supply-coordination-number nil
               :jurisdiction "USA" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-installation!
  "Backend-agnostic `:site/mark-scheduled` -- looks up the site via the
  protocol and drafts the installation-schedule record, and returns
  {:result .. :site-patch ..} for the caller to persist."
  [s site-id]
  (let [c (site s site-id)
        seq-n (next-schedule-sequence s (:jurisdiction c))
        result (registry/register-installation-schedule site-id (:jurisdiction c) seq-n)]
    {:result result
     :site-patch {:scheduled? true
                  :schedule-number (get result "schedule_number")}}))

(defn- coordinate-supply!
  "Backend-agnostic `:site/mark-supply-coordinated` -- looks up the site
  via the protocol and drafts the equipment-supply-coordination
  record, and returns {:result .. :site-patch ..} for the caller to
  persist."
  [s site-id]
  (let [c (site s site-id)
        seq-n (next-supply-sequence s (:jurisdiction c))
        result (registry/register-supply-coordination site-id (:jurisdiction c) seq-n)]
    {:result result
     :site-patch {:supply-coordination-open? true
                  :supply-coordination-number (get result "supply_coordination_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (site [_ id] (get-in @a [:sites id]))
  (all-sites [_] (sort-by :id (vals (:sites @a))))
  (ledger [_] (:ledger @a))
  (schedule-history [_] (:schedules @a))
  (supply-history [_] (:supplies @a))
  (next-schedule-sequence [_ jurisdiction] (get-in @a [:schedule-sequences jurisdiction] 0))
  (next-supply-sequence [_ jurisdiction] (get-in @a [:supply-sequences jurisdiction] 0))
  (site-already-scheduled? [_ site-id] (boolean (get-in @a [:sites site-id :scheduled?])))
  (site-supply-already-open? [_ site-id] (boolean (get-in @a [:sites site-id :supply-coordination-open?])))
  (commit-record! [s {:keys [action path value]}]
    (case action
      :site/log
      (let [site-id (first path)
            {:keys [patch spec-basis legal-basis]} value]
        (swap! a update-in [:sites site-id]
               merge (assoc patch
                            :registered? (some? spec-basis)
                            :spec-basis spec-basis
                            :legal-basis legal-basis)))

      :site/flag-security-concern
      (let [site-id (first path)
            {:keys [note]} value]
        (swap! a update-in [:sites site-id]
               merge {:security-concern-raised? true
                      :security-concern-resolved? false
                      :security-concern-note note}))

      :site/mark-scheduled
      (let [site-id (first path)
            {:keys [result site-patch]} (schedule-installation! s site-id)
            jurisdiction (:jurisdiction (site s site-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:schedule-sequences jurisdiction] (fnil inc 0))
                       (update-in [:sites site-id] merge site-patch)
                       (update :schedules registry/append result))))
        result)

      :site/mark-supply-coordinated
      (let [site-id (first path)
            {:keys [result site-patch]} (coordinate-supply! s site-id)
            jurisdiction (:jurisdiction (site s site-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:supply-sequences jurisdiction] (fnil inc 0))
                       (update-in [:sites site-id] merge site-patch)
                       (update :supplies registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s))

(defn seed-db
  "A MemStore seeded with the demo site set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger [] :schedule-sequences {} :schedules []
                           :supply-sequences {} :supplies []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (ledger facts, schedule/supply records) are
  stored as EDN strings so `langchain.db` doesn't expand them into
  sub-entities -- the same convention every sibling actor's store
  uses."
  {:site/id                       {:db/unique :db.unique/identity}
   :ledger/seq                    {:db/unique :db.unique/identity}
   :schedule/seq                  {:db/unique :db.unique/identity}
   :supply/seq                    {:db/unique :db.unique/identity}
   :schedule-sequence/jurisdiction {:db/unique :db.unique/identity}
   :supply-sequence/jurisdiction   {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- site->tx [{:keys [id client address system-types requires-permit?
                         registered? spec-basis legal-basis
                         permit-confirmed?
                         security-concern-raised? security-concern-resolved? security-concern-note
                         scheduled? schedule-number
                         supply-coordination-open? supply-coordination-number
                         jurisdiction status]}]
  (cond-> {:site/id id}
    client                                        (assoc :site/client client)
    address                                          (assoc :site/address address)
    system-types                                       (assoc :site/system-types (enc system-types))
    (some? requires-permit?)                             (assoc :site/requires-permit? requires-permit?)
    (some? registered?)                                    (assoc :site/registered? registered?)
    spec-basis                                               (assoc :site/spec-basis spec-basis)
    legal-basis                                                (assoc :site/legal-basis legal-basis)
    (some? permit-confirmed?)                                    (assoc :site/permit-confirmed? permit-confirmed?)
    (some? security-concern-raised?)                               (assoc :site/security-concern-raised? security-concern-raised?)
    (some? security-concern-resolved?)                               (assoc :site/security-concern-resolved? security-concern-resolved?)
    security-concern-note                                              (assoc :site/security-concern-note security-concern-note)
    (some? scheduled?)                                                   (assoc :site/scheduled? scheduled?)
    schedule-number                                                        (assoc :site/schedule-number schedule-number)
    (some? supply-coordination-open?)                                        (assoc :site/supply-coordination-open? supply-coordination-open?)
    supply-coordination-number                                                 (assoc :site/supply-coordination-number supply-coordination-number)
    jurisdiction                                                                 (assoc :site/jurisdiction jurisdiction)
    status                                                                         (assoc :site/status status)))

(def ^:private site-pull
  [:site/id :site/client :site/address :site/system-types
   :site/requires-permit? :site/registered? :site/spec-basis :site/legal-basis
   :site/permit-confirmed?
   :site/security-concern-raised? :site/security-concern-resolved? :site/security-concern-note
   :site/scheduled? :site/schedule-number
   :site/supply-coordination-open? :site/supply-coordination-number
   :site/jurisdiction :site/status])

(defn- pull->site [m]
  (when (:site/id m)
    {:id (:site/id m) :client (:site/client m) :address (:site/address m)
     :system-types (dec* (:site/system-types m))
     :requires-permit? (boolean (:site/requires-permit? m))
     :registered? (boolean (:site/registered? m))
     :spec-basis (:site/spec-basis m) :legal-basis (:site/legal-basis m)
     :permit-confirmed? (boolean (:site/permit-confirmed? m))
     :security-concern-raised? (boolean (:site/security-concern-raised? m))
     :security-concern-resolved? (boolean (:site/security-concern-resolved? m))
     :security-concern-note (:site/security-concern-note m)
     :scheduled? (boolean (:site/scheduled? m)) :schedule-number (:site/schedule-number m)
     :supply-coordination-open? (boolean (:site/supply-coordination-open? m))
     :supply-coordination-number (:site/supply-coordination-number m)
     :jurisdiction (:site/jurisdiction m) :status (:site/status m)}))

(defrecord DatomicStore [conn]
  Store
  (site [_ id]
    (pull->site (d/pull (d/db conn) site-pull [:site/id id])))
  (all-sites [_]
    (->> (d/q '[:find [?id ...] :where [?e :site/id ?id]] (d/db conn))
         (map #(pull->site (d/pull (d/db conn) site-pull [:site/id %])))
         (sort-by :id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (schedule-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :schedule/seq ?s] [?e :schedule/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (supply-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :supply/seq ?s] [?e :supply/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-schedule-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :schedule-sequence/jurisdiction ?j] [?e :schedule-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-supply-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :supply-sequence/jurisdiction ?j] [?e :supply-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (site-already-scheduled? [s site-id]
    (boolean (:scheduled? (site s site-id))))
  (site-supply-already-open? [s site-id]
    (boolean (:supply-coordination-open? (site s site-id))))
  (commit-record! [s {:keys [action path value]}]
    (case action
      :site/log
      (let [site-id (first path)
            {:keys [patch spec-basis legal-basis]} value]
        (d/transact! conn [(site->tx (assoc (merge (site s site-id) patch)
                                            :id site-id
                                            :registered? (some? spec-basis)
                                            :spec-basis spec-basis
                                            :legal-basis legal-basis))]))

      :site/flag-security-concern
      (let [site-id (first path)
            {:keys [note]} value]
        (d/transact! conn [(site->tx (assoc (site s site-id)
                                            :id site-id
                                            :security-concern-raised? true
                                            :security-concern-resolved? false
                                            :security-concern-note note))]))

      :site/mark-scheduled
      (let [site-id (first path)
            {:keys [result site-patch]} (schedule-installation! s site-id)
            jurisdiction (:jurisdiction (site s site-id))
            next-n (inc (next-schedule-sequence s jurisdiction))]
        (d/transact! conn
                     [(site->tx (assoc (merge (site s site-id) site-patch) :id site-id))
                      {:schedule-sequence/jurisdiction jurisdiction :schedule-sequence/next next-n}
                      {:schedule/seq (count (schedule-history s)) :schedule/record (enc (get result "record"))}])
        result)

      :site/mark-supply-coordinated
      (let [site-id (first path)
            {:keys [result site-patch]} (coordinate-supply! s site-id)
            jurisdiction (:jurisdiction (site s site-id))
            next-n (inc (next-supply-sequence s jurisdiction))]
        (d/transact! conn
                     [(site->tx (assoc (merge (site s site-id) site-patch) :id site-id))
                      {:supply-sequence/jurisdiction jurisdiction :supply-sequence/next next-n}
                      {:supply/seq (count (supply-history s)) :supply/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-sites [s sites]
    (when (seq sites) (d/transact! conn (mapv site->tx (vals sites)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:sites ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [sites]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-sites s sites))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo site set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
