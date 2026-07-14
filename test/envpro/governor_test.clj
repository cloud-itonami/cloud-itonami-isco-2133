(ns envpro.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [envpro.governor :as governor]
            [envpro.store :as store]))

(deftest accepts-low-confidence-proposal-with-escalation
  (let [st (store/mem-store)
        _ (store/register-project! st {:project-id "proj-1" :title "Test"})
        _ (store/register-monitoring-site! st {:site-id "site-1" :project-id "proj-1"})
        request {:project-id "proj-1" :site-id "site-1" :op :analyze-monitoring-data}
        proposal {:op :analyze-monitoring-data :effect :propose :confidence 0.5 :stake :low}
        result (governor/check request {} proposal st)]
    (is (false? (:ok? result)))
    (is (true? (:escalate? result)))
    (is (empty? (:violations result)))))

(deftest rejects-missing-project-as-hard-violation
  (let [st (store/mem-store)
        request {:project-id "no-proj" :op :analyze-monitoring-data}
        proposal {:op :analyze-monitoring-data :effect :propose :confidence 0.9 :stake :low}
        result (governor/check request {} proposal st)]
    (is (false? (:ok? result)))
    (is (true? (:hard? result)))
    (is (some #(= :no-project (:rule %)) (:violations result)))))

(deftest rejects-missing-monitoring-site-for-analyze-op
  (let [st (store/mem-store)
        _ (store/register-project! st {:project-id "proj-1" :title "Test"})
        request {:project-id "proj-1" :site-id "no-site" :op :analyze-monitoring-data}
        proposal {:op :analyze-monitoring-data :effect :propose :confidence 0.9}
        result (governor/check request {} proposal st)]
    (is (false? (:ok? result)))
    (is (true? (:hard? result)))
    (is (some #(= :no-monitoring-site (:rule %)) (:violations result)))))

(deftest rejects-missing-monitoring-record-when-record-id-provided
  (let [st (store/mem-store)
        _ (store/register-project! st {:project-id "proj-1" :title "Test"})
        _ (store/register-monitoring-site! st {:site-id "site-1" :project-id "proj-1"})
        request {:project-id "proj-1" :site-id "site-1" :monitoring-record-id "no-record" :op :analyze-monitoring-data}
        proposal {:op :analyze-monitoring-data :effect :propose :confidence 0.9}
        result (governor/check request {} proposal st)]
    (is (false? (:ok? result)))
    (is (true? (:hard? result)))
    (is (some #(= :no-monitoring-record (:rule %)) (:violations result)))))

(deftest rejects-non-propose-effect-as-hard-violation
  (let [st (store/mem-store)
        _ (store/register-project! st {:project-id "proj-1" :title "Test"})
        request {:project-id "proj-1" :op :analyze-monitoring-data}
        proposal {:op :analyze-monitoring-data :effect :commit :confidence 0.9}
        result (governor/check request {} proposal st)]
    (is (false? (:ok? result)))
    (is (true? (:hard? result)))
    (is (some #(= :no-actuation (:rule %)) (:violations result)))))

(deftest rejects-finalized-report-as-hard-violation
  (let [st (store/mem-store)
        _ (store/register-project! st {:project-id "proj-1" :title "Test"})
        request {:project-id "proj-1" :op :draft-report}
        proposal {:op :draft-report :effect :propose :confidence 0.9 :finalized? true}
        result (governor/check request {} proposal st)]
    (is (false? (:ok? result)))
    (is (true? (:hard? result)))
    (is (some #(= :no-finalized-claims (:rule %)) (:violations result)))))

(deftest escalates-on-contamination-risk-flag
  (let [st (store/mem-store)
        _ (store/register-project! st {:project-id "proj-1" :title "Test"})
        request {:project-id "proj-1" :op :flag-contamination-risk}
        proposal {:op :flag-contamination-risk :effect :propose :confidence 0.9 :contamination-type :heavy-metals}
        result (governor/check request {} proposal st)]
    (is (false? (:ok? result)))
    (is (true? (:escalate? result)))
    (is (empty? (:violations result)))))

(deftest escalates-on-significant-finding-in-report
  (let [st (store/mem-store)
        _ (store/register-project! st {:project-id "proj-1" :title "Test"})
        request {:project-id "proj-1" :op :draft-report}
        proposal {:op :draft-report :effect :propose :confidence 0.8 :significant-finding? true}
        result (governor/check request {} proposal st)]
    (is (false? (:ok? result)))
    (is (true? (:escalate? result)))
    (is (empty? (:violations result)))))

(deftest accepts-clean-monitoring-analysis-with-high-confidence
  (let [st (store/mem-store)
        _ (store/register-project! st {:project-id "proj-1" :title "Test"})
        _ (store/register-monitoring-site! st {:site-id "site-1" :project-id "proj-1"})
        request {:project-id "proj-1" :site-id "site-1" :op :analyze-monitoring-data}
        proposal {:op :analyze-monitoring-data :effect :propose :confidence 0.95 :stake :low}
        result (governor/check request {} proposal st)]
    (is (true? (:ok? result)))
    (is (false? (:hard? result)))
    (is (false? (:escalate? result)))))
