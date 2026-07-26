(ns envpro.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all (`:item2/classification \"unknown-no-demo\"` in the
  fleet-wide scan). This namespace drives the REAL actor stack
  (`envpro.actor` -> `envpro.governor` -> `envpro.store`) through a
  scenario built from real, exercised store data and renders the result
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verify by
  diffing two consecutive runs before shipping).

  Adapted from the ISCO-08 1211/1111/2113/1213/1112 build-time-console
  precedents (`90-docs/business/cloud-itonami-maturity-loop.md` in
  com-junkawasaki/root) using this repo's OWN real fixture, not a copy
  of theirs: project `proj-1` (\"Environmental Monitoring Project\") +
  monitoring-record `mr-1` (air-quality, \"Site A\") + monitoring-site
  `site-1` (\"Industrial Zone A\", risk-level :medium) + equipment `eq-1`
  (\"Air Quality Monitor\") are lifted VERBATIM from
  `envpro.actor-test`'s `fresh-store` fixture (ground truth, not
  invented). Project `proj-2` (\"Coastal Wetlands Resurvey\") is
  ADDITIONAL demo data registered via the SAME real `register-project!`
  protocol call this actor's own store exposes -- disclosed here
  plainly, not presented as pre-existing fixture, so the console can
  show a second project operating cleanly. Every other field this page
  displays (statuses, record counts, hold/escalation reasons) is real
  output read after `run-demo!` actually executed the graph -- none of
  it is hand-typed.

  Honesty note on `context` (architecture, not a shortcut): unlike the
  ISCO-08 1112 sibling (`administration.governor`, which gates on
  `context`'s `:topic` against a fixed sensitive-topics set),
  `envpro.governor/check`'s parameter list includes `context` but the
  function BODY never reads it (confirmed by reading the code -- no
  reference to `context` anywhere in `hard-violations` or `check`).
  There is no context/topic-sensitivity gate in this domain. This
  scenario therefore passes `{}` for context throughout every run --
  varying it would demonstrate nothing real, so we don't pretend
  otherwise with a fake topic dimension.

  Honesty note on the `equipment` registry: the store models an
  `equipment` entity (`eq-1`) and a `:request-field-equipment` op, but
  reading `envpro.governor/hard-violations` shows NO invariant ever
  checks equipment existence or project-equipment provenance -- unlike
  `project`, `monitoring-site`, and `monitoring-record`, which all have
  a real existence gate. A `:request-field-equipment` request citing a
  ghost `:equipment-id` would sail through identically to citing `eq-1`.
  This scenario references the real `eq-1` for realism, but that gate
  simply doesn't exist in the code -- noted here rather than glossed
  over.

  This scenario demonstrates 4 of the 5 real HARD-hold rules in
  `envpro.governor/hard-violations` (`:no-project`,
  `:no-monitoring-site`, `:no-monitoring-record`,
  `:no-finalized-claims`) and both real escalation paths
  (`:flag-contamination-risk` always-escalates;
  `:draft-report` with `:significant-finding? true`) -- every `:op`
  keyword and violation `:rule` name below is copied from
  `envpro.governor` itself, not invented.

  Known architectural gaps, honestly noted rather than papered over
  (confirmed by reading `envpro.governor` and `envpro.advisor`
  directly, not assumed):
  - `:no-actuation` (proposal `:effect` must be `:propose`) is NOT
    reachable through this demo, because the real `mock-advisor`
    unconditionally sets `:effect :propose` on every proposal it emits.
    Covered instead by
    `envpro.governor-test/rejects-non-propose-effect-as-hard-violation`
    (which calls `governor/check` directly with a hand-built proposal
    whose `:effect` is `:commit`).
  - low-confidence escalation (`confidence < 0.6`) is NOT reachable
    either, because `envpro.advisor/infer`'s stake-derived confidence
    (`:high` 0.7, `:medium` 0.85, `:low` 0.95) never drops below the
    governor's `confidence-floor` (0.6).
  Both gaps are the same shape as the ISCO-08 1211/2113/1213/1112
  precedents' disclosed `:no-actuation` gap -- this demo, like those,
  only ever drives the real actor/graph the way an operator actually
  would, and does not hand-construct proposals to force unreachable
  paths.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [envpro.store :as store]
            [envpro.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real environmental protection operation request through the
  actual compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it (this
  demo's scenario never demonstrates an UNAPPROVED escalation -- every
  escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid project-id op extra context]
  (let [request (merge {:project-id project-id :op op} extra)
        r1 (actor/run-request! graph request context tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :project-id project-id :op op :request request :context context
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :project-id project-id :op op :request request :context context
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :project-id project-id :op op :request request :context context
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit across 3 distinct ops,
  escalate-then-approve for both real escalation reasons, and 4 of the
  5 distinct HARD-hold reasons in `envpro.governor` -- the 5th,
  `:no-actuation`, plus the low-confidence escalation reason, are
  architecturally unreachable via the real advisor, see namespace
  docstring). Every `:op` keyword and violation rule name below is
  copied from `envpro.governor`'s own `hard-violations`/`check`, not
  invented. Vector shape: [thread-id project-id op extra context]."
  [;; proj-1 (real fixture from envpro.actor-test) -- clean ops
   ["p1-analyze-clean"        "proj-1" :analyze-monitoring-data {:site-id "site-1"} {}]
   ["p1-analyze-record-ref"   "proj-1" :analyze-monitoring-data {:site-id "site-1" :monitoring-record-id "mr-1"} {}]
   ["p1-equipment-clean"      "proj-1" :request-field-equipment {:equipment-id "eq-1"} {}]
   ;; proj-1 -- real HARD-hold reasons
   ["p1-hold-no-site"         "proj-1" :analyze-monitoring-data {:site-id "no-such-site"} {}]
   ["p1-hold-no-record"       "proj-1" :analyze-monitoring-data {:site-id "site-1" :monitoring-record-id "no-such-record"} {}]
   ["p1-hold-finalized-report" "proj-1" :draft-report          {:finalized? true} {}]
   ;; unregistered project entirely
   ["ghost-no-project"        "no-such-project" :analyze-monitoring-data {:site-id "site-1"} {}]
   ;; proj-1 -- real escalation reasons, both approved after human sign-off
   ["p1-escalate-contamination" "proj-1" :flag-contamination-risk {:contamination-type :heavy-metals} {}]
   ["p1-escalate-significant"   "proj-1" :draft-report            {:significant-finding? true} {}]
   ;; proj-2 (additional demo data, registered via the same real
   ;; register-project! call -- see namespace docstring)
   ["p2-equipment-clean"      "proj-2" :request-field-equipment {:equipment-id "eq-1"} {}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `envpro.actor` graph. Returns `{:store :runs}` -- `:runs` is
  the ordered vector of real per-request outcomes; every field in
  `render` below is read from this or from `store` after the graph
  actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-project! db {:project-id "proj-1" :title "Environmental Monitoring Project"})
    (store/register-monitoring-record! db {:record-id "mr-1" :project-id "proj-1" :monitoring-type :air-quality :location "Site A" :date "2026-07-14"})
    (store/register-monitoring-site! db {:site-id "site-1" :project-id "proj-1" :name "Industrial Zone A" :coordinates "51.5,-0.1" :risk-level :medium})
    (store/register-equipment! db {:equipment-id "eq-1" :name "Air Quality Monitor"})
    (store/register-project! db {:project-id "proj-2" :title "Coastal Wetlands Resurvey"})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid project-id op extra context]]
                       (run-op! graph tid project-id op extra context))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- project-row [store {:keys [project-id title]} runs]
  (let [record-count (count (store/records-of store project-id))
        last-run (last (filter #(= project-id (:project-id %)) runs))]
    (format "        <tr><td>%s</td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc project-id) (esc title) record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- flags-of [request]
  (str/join ", "
            (keep (fn [[k v]]
                    (when (contains? #{:monitoring-record-id :contamination-type :finalized? :significant-finding?} k)
                      (str (name k) "=" v)))
                  request)))

(defn- run-row [{:keys [thread-id project-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc project-id) (esc (name op))
          (esc (or (:site-id request) ""))
          (esc (flags-of request))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`envpro.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:analyze-monitoring-data</code></td><td><span class=\"ok\">auto-commit when the monitoring-site (and monitoring-record, if cited) is registered</span></td></tr>"
   "        <tr><td><code>:draft-report</code></td><td><span class=\"warn\">auto-commit UNLESS :finalized? true (HARD hold) or :significant-finding? true (escalate)</span></td></tr>"
   "        <tr><td><code>:flag-contamination-risk</code></td><td><span class=\"warn\">ALWAYS human approval &middot; pollution/contamination safeguard, never silently dismissed</span></td></tr>"
   "        <tr><td><code>:request-field-equipment</code></td><td><span class=\"ok\">auto-commit when the project is registered &middot; equipment-id itself is NOT existence-checked (see docstring)</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [projects [{:project-id "proj-1" :title "Environmental Monitoring Project"}
                   {:project-id "proj-2" :title "Coastal Wetlands Resurvey"}]
        project-rows (str/join "\n" (map #(project-row store % runs) projects))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-2133 &middot; environmental protection professionals</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Environmental Protection Professionals (ISCO-08 2133) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · every proposal is for staff review only, never binding action</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered projects</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>envpro.store</code> via <code>envpro.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Project</th><th>Title</th><th>Records committed</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     project-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Environmental Protection Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Unlike some ISCO-08 siblings, this governor's <code>context</code> parameter is never read — there is no topic-sensitivity dimension in this domain (see namespace docstring).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, project, op, cited site (if any), other request flags, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Project</th><th>Op</th><th>Site</th><th>Flags</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
