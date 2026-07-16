(ns secsys.facts
  "Per-jurisdiction security-systems-services licensing catalog -- the
  G2-style spec-basis table the Security Systems Governor checks every
  `:log-monitoring-record` proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's electronic-security-
  systems licensing/registration requirements, or did it invent one?').

  This repo's own README `Scope note` already identifies the exact
  regulatory facts this catalog encodes -- electronic security-systems
  services (alarm/CCTV/access-control installation and monitoring) are
  licensed SEPARATELY from personnel-based guarding in every
  jurisdiction checked: California's Bureau of Security and
  Investigative Services (BSIS) issues a distinct 'Alarm Company
  Operator' license; the UK's Security Industry Authority (SIA)
  licenses 'Public Space Surveillance (CCTV)' separately from door
  supervision/guarding; Japan's 警備業法 (Security Business Act) treats
  機械警備業務 (mechanical/systems security) as its own registration
  category (機械警備業務開始届出書). This namespace does not extend that
  set with anything not already asserted in the README -- adding
  coverage means adding a real, citable catalog entry, never
  fabricating one.

  Like every sibling actor's `facts` namespace, coverage is reported
  HONESTLY (see `coverage`): a jurisdiction not in this table has NO
  spec-basis, full stop -- the advisor must not fabricate one, and the
  governor holds if it tries.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  client-site/monitored-system registration evidence set (PLUS an
  installation-permit/compliance-check confirmation record);
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any `:log-monitoring-record`
  proposal can commit."
  {"USA" {:name "United States (California)"
          :owner-authority "California Bureau of Security and Investigative Services (BSIS)"
          :legal-basis "California Business and Professions Code, Chapter 11.6 (Alarm Company Act) -- \"Alarm Company Operator\" license"
          :national-spec "BSIS Alarm Company Operator licensing and monitored-alarm-response registration requirements"
          :provenance "https://www.bsis.ca.gov/"
          :required-evidence ["client-site registration record"
                               "monitored-system (alarm/CCTV/access-control) inventory record"
                               "alarm-company-operator license reference"
                               "installation-permit/compliance-check confirmation record"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Security Industry Authority (SIA)"
          :legal-basis "Private Security Industry Act 2001 -- \"Public Space Surveillance (CCTV)\" licensable activity"
          :national-spec "SIA licensing standards for CCTV/electronic security-systems operators, distinct from door-supervision/guarding licensing"
          :provenance "https://www.gov.uk/government/organisations/security-industry-authority"
          :required-evidence ["client-site registration record"
                               "monitored-system (alarm/CCTV/access-control) inventory record"
                               "SIA public-space-surveillance licence reference"
                               "installation-permit/compliance-check confirmation record"]}
   "JPN" {:name "Japan"
          :owner-authority "都道府県公安委員会 (Prefectural Public Safety Commission) / 警察庁 (National Police Agency)"
          :legal-basis "警備業法 (Security Business Act) 第2条第1項第4号 (機械警備業務) -- 機械警備業務開始届出書"
          :national-spec "機械警備業務の実施の基準 (mechanical/systems-security operating standard), including監視員の待機・対応時間基準"
          :provenance "https://www.npa.go.jp/"
          :required-evidence ["顧客先登録記録 (client-site registration record)"
                               "監視対象システム記録 (monitored-system inventory record)"
                               "機械警備業務開始届出 reference"
                               "設置工事許可・適合確認記録 (installation-permit/compliance-check confirmation record)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to register a
  client-site/monitored-system record on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-8020 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `secsys.facts/catalog`, "
                 "never fabricate a jurisdiction's licensing requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
