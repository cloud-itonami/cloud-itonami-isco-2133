(ns envpro.governor
  "EnvironmentalProtectionGovernor — the independent safety/traceability
  layer for the ISCO-08 2133 environmental protection actor. Wired as its
  own `:govern` node in `envpro.actor`'s StateGraph, downstream of `:advise`
  — the Advisor has no notion of project provenance or site safety risk, so
  this MUST be a separate system able to reject a proposal (itonami actor
  pattern, per ADR-2607011000 / CLAUDE.md Actors section).

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. project provenance  — the request's project must be registered.
    2. monitoring-site verification — :analyze-monitoring-data ops must
                                     reference a registered monitoring-site.
    3. monitoring-record verification — ops referencing monitoring data must
                                       reference a registered
                                       monitoring-record.
    4. no-actuation — proposal :effect must be :propose.
    5. no-finalized-claims — :draft-report proposals can never claim
                             a result as finalized/publishable or regulatory
                             determination as final (draft is draft-for-review
                             only, not the final-report proposal).

  ESCALATION invariants (:escalate? true, ALWAYS human sign-off):
    6. :flag-contamination-risk — always escalates (pollution/contamination
                                 safeguard, never silently dismissed).
    7. :draft-report with :significant-finding? true — significant
                                                      environmental findings
                                                      require human review
                                                      before proceeding.
    8. low confidence (< `confidence-floor`)."
  (:require [envpro.store :as store]))

(def confidence-floor 0.6)
(def ^:private escalating-ops #{:flag-contamination-risk :draft-report})

(defn- hard-violations [{:keys [proposal request]} project-record monitoring-record site-record]
  (cond-> []
    (nil? project-record)
    (conj {:rule :no-project :detail "未登録 project"})

    (and (= :analyze-monitoring-data (:op proposal))
         (nil? site-record))
    (conj {:rule :no-monitoring-site :detail "analyze-monitoring-data 前に monitoring-site は要登録"})

    (and (or (= :analyze-monitoring-data (:op proposal))
             (= :flag-contamination-risk (:op proposal)))
         (:monitoring-record-id request)
         (nil? monitoring-record))
    (conj {:rule :no-monitoring-record :detail "monitoring-record reference がある場合、monitoring-record は要登録"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

    (and (= :draft-report (:op proposal))
         (:finalized? proposal))
    (conj {:rule :no-finalized-claims :detail "report 最終化や規制判定確定は draft 提案では不可（draft は査読用のみ）"})))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `envpro.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [project-record (store/project store (:project-id request))
        monitoring-record (when (:monitoring-record-id request) (store/monitoring-record store (:monitoring-record-id request)))
        site-record (when (:site-id request) (store/monitoring-site store (:site-id request)))
        hard (hard-violations {:proposal proposal :request request} project-record monitoring-record site-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        is-contamination-flag? (= :flag-contamination-risk (:op proposal))
        is-draft-significant? (and (= :draft-report (:op proposal)) (:significant-finding? proposal))
        risky-op? (and (contains? escalating-ops (:op proposal))
                       (or is-contamination-flag? is-draft-significant?))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
