# cloud-itonami-isco-2133

**ISCO-08 Unit Group 2133: Environmental Protection Professionals**

A field and laboratory environmental assessment support actor implementing the itonami actor pattern (independent `EnvironmentalProtectionGovernor`, langgraph-clj StateGraph, append-only audit ledger) for environmental monitoring, contamination assessment, and environmental protection operations.

## Architecture

The actor is decomposed into:

1. **EnvironmentalAdvisor** (`envpro.advisor`) — proposes environmental operations:
   - `:analyze-monitoring-data` — pipeline analysis proposal over recorded environmental monitoring data
   - `:draft-report` — prepare an environmental assessment report for review
   - `:flag-contamination-risk` — surface contamination/pollution risk findings (always escalates)
   - `:request-field-equipment` — propose monitoring equipment allocation

2. **EnvironmentalProtectionGovernor** (`envpro.governor`) — independent verification layer:
   - HARD invariants (`:hold`, never overridable):
     - Project provenance (registered project)
     - Monitoring-site registration (required for analysis)
     - Monitoring-record registration (if referenced in operation)
     - Proposal effect must be `:propose` (no direct actuation)
     - No finalized claims in draft reports (or regulatory determinations as final)
   - ESCALATION (always human sign-off):
     - `:flag-contamination-risk` always escalates
     - `:draft-report` with significant environmental findings
     - Low confidence (< 0.6)

3. **EnvironmentalActor** (`envpro.actor`) — langgraph StateGraph:
   ```text
   :intake → :advise → :govern → :decide ─┬─→ :commit           (ok)
                                             ├─→ :request-approval  (escalate)
                                             └─→ :hold              (hard violation)
   ```

4. **Store** (`envpro.store`) — single source of truth protocol:
   - `project`, `monitoring-record`, `monitoring-site`, `equipment`, `record`, `ledger`
   - `MemStore` (in-memory, default); swappable for Datomic/kotoba-server

## Operations

All proposals carry:
- `:op` — operation type
- `:effect :propose` — always (no direct writes)
- `:stake` — `:low`, `:medium`, or `:high`
- `:confidence` — 0.0–1.0 (LLM advisor)
- `:rationale` — operation description

## Testing

```bash
clj -M:test
```

Deterministic mock advisor (`:mock-advisor`, default) or real LLM-backed (`llm-advisor`).

## Graph State

| Channel | Type | Role |
|---------|------|------|
| `:request` | map | incoming operation request |
| `:context` | map | execution context |
| `:proposal` | map | advisor's recommendation |
| `:verdict` | map | governor's assessment |
| `:disposition` | keyword | `:commit`, `:request-approval`, or `:hold` |
| `:record` | map | committed operation record |
| `:audit` | vector | per-node ledger entries |

## Checkpointing & Human Approval

Escalated proposals interrupt before `:request-approval` node; resume after human sign-off via `approve!`:

```clojure
(let [graph (build-graph {:store st})
      interrupted (run-request! graph request {} "thread-1")]
  ;; ... human review ...
  (approve! graph "thread-1"))  ;; advance to :commit
```

All outcomes (commit, hold, escalation) logged to store's append-only ledger.

## License

AGPL-3.0-or-later. See LICENSE.
