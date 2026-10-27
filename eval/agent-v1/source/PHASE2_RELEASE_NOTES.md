# Phase 2 Complete Release

The `TPC2026_phase2` config publishes the complete 2,000-query English Phase 2 batch.
It covers all 53 constraint templates in the public ChinaTravel generation
pipeline. It has two splits:

- `full`: all 2,000 records;
- `competition_test`: the 100 records used for the official competition
  evaluation.

The same 100 records are marked by `official_phase2_evaluation=true` in `full`.

## Validation

The source batch was checked with the current evaluator and canonical English
sandbox database:

- 2,000/2,000 records passed schema and DSL execution;
- 2,000/2,000 copied oracle plans passed every hard constraint;
- 2,000/2,000 copied oracle plans passed all commonsense checks;
- all 2,000 UIDs, query texts, and DSL signatures are unique;
- all 53 published constraint keys are covered;
- no empty or truncated natural-language field, invalid time, missing DSL
  literal, non-canonical concept label, or unsupported DSL syntax was found.

The 53 natural-language templates were also reviewed against their generator
implementations. The public export makes the following semantics explicit:

- OR is inclusive: either or both branches may be satisfied;
- monetary limits are in CNY;
- in-city transport constraints cover the complete itinerary rather than only
  the destination city;
- per-mode journey counts use the primary mode assigned to an activity's
  inbound in-city transport chain;
- time-window constraints require the complete activity interval to lie inside
  the stated window;
- attraction-duration constraints apply to the total time across attractions.

## Identity-Preserving Cleanup

In the competition export, 930 OR constraints across 776 records repeated one
of their branches as an independent hard constraint in the same query. For
example, a query could require `A` and also contain `(A OR B)`. The public
release removes the redundant OR in exactly those cases:

```text
A AND (A OR B) == A
```

This cleanup does not change the set of plans that satisfy the complete query.
The generation pipeline now prevents an OR and either of its atomic branches
from being selected together.

There are 254 records with compatible constraints that target the same entity,
such as a day placement and an exact time. These are retained because they test
different semantics, are not contradictory, and are all satisfied by the
audited oracle plans.

## Reproducibility

The machine-readable [`phase2_audit_report.json`](phase2_audit_report.json)
contains aggregate counts, every UID affected by the OR cleanup, the source
seed-plan audit summary, per-shard SHA-256 checksums, and the SHA-256 checksum
of a canonical JSONL serialization of all rows in release order.

The Hub config uses CSV shards to remain compatible with the repository's
existing CSV builder. The three ordered list fields (`hard_logic_py`,
`hard_logic_nl`, and `constraint_keys`) contain JSON text and can be restored
losslessly with `json.loads`.

The release intentionally excludes copied oracle plans, generator metadata,
local paths, and generation traces.

Use this release with the latest companion `ChinaTravel-Sandbox`. Older sandbox
snapshots do not contain the canonical English entity and concept labels used
by the audited DSL.
