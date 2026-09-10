(ns envpro.store
  "SSoT for the ISCO-08 2133 environmental protection actor.
  Store is a protocol injected into the `envpro.actor`
  StateGraph — `MemStore` is the default, deterministic, zero-dep
  backend; a Datomic/kotoba-server-backed implementation can be
  swapped in without touching the actor or governor (itonami actor
  pattern, per ADR-2607011000 / CLAUDE.md Actors section).

  Domain:

    project  — a registered environmental monitoring/protection project
               (:project-id, :title)
    monitoring-record — a recorded environmental data point from field or lab
                        (:record-id, :project-id, :monitoring-type,
                        :location, :date)
    monitoring-site — a field survey/monitoring location
                      (:site-id, :project-id, :name, :coordinates,
                      :risk-level)
    equipment — environmental monitoring equipment resource
                (:equipment-id, :name)
    record   — a committed environmental operation under a project
               (monitoring analysis, assessment report draft,
               contamination risk flag, equipment request) — written
               ONLY via commit-record!, never mutated in place
    ledger   — an append-only audit trail of every proposal/verdict/
               disposition, regardless of outcome (commit or hold)")

(defprotocol Store
  (project [s project-id])
  (monitoring-record [s record-id])
  (monitoring-site [s site-id])
  (equipment [s equipment-id])
  (records-of [s project-id])
  (ledger [s])
  (register-project! [s project])
  (register-monitoring-record! [s record])
  (register-monitoring-site! [s site])
  (register-equipment! [s equipment])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (project [_ project-id] (get-in @a [:projects project-id]))
  (monitoring-record [_ record-id] (get-in @a [:monitoring-records record-id]))
  (monitoring-site [_ site-id] (get-in @a [:monitoring-sites site-id]))
  (equipment [_ equipment-id] (get-in @a [:equipment equipment-id]))
  (records-of [_ project-id] (filter #(= project-id (:project-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-project! [s project]
    (swap! a assoc-in [:projects (:project-id project)] project) s)
  (register-monitoring-record! [s record]
    (swap! a assoc-in [:monitoring-records (:record-id record)] record) s)
  (register-monitoring-site! [s site]
    (swap! a assoc-in [:monitoring-sites (:site-id site)] site) s)
  (register-equipment! [s equipment]
    (swap! a assoc-in [:equipment (:equipment-id equipment)] equipment) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:projects {} :monitoring-records {} :monitoring-sites {} :equipment {} :records [] :ledger []} seed)))))
