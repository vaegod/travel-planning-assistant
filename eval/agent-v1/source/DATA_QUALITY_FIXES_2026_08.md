# ChinaTravel Data Quality Corrections (2026-08-20)

## Scope

This maintenance release aligns the public query files with the canonical
bilingual sandbox inventory and the current evaluator. It changes only records
with a verified query, DSL, metadata, or inventory inconsistency.

## Query Corrections

- Corrected 28 Phase 1 records: 8 in `easy` and 20 in `human`.
- Standardized executable Chinese cuisine constraints on the sandbox value
  `本帮菜`. Natural-language wording such as “上海菜”, “本帮菜”, “上海特色美食”,
  and “当地特色菜” remains unchanged to preserve linguistic diversity.
- Corrected unsupported or contradictory concept constraints, canonical POI
  names, infeasible hotel/room/budget combinations, one destination mismatch,
  three uses of the undefined `people_number` DSL variable, and one inconsistent
  traveler/taxi count.

The machine-readable
[`DATA_QUALITY_REPAIRS_2026_08.json`](DATA_QUALITY_REPAIRS_2026_08.json)
records every affected UID, changed field, reason, and resulting file checksum.

## Public Dataset Audit

- Parsed all 33,815 executable constraints across all public CSV configs with
  zero parse failures.
- Checked 4,054 public rows, including Phase 1, preference, `human1000`, the
  2,000-query Phase 2 release, and its 100-query competition subset.
- Found no remaining executable use of the noncanonical Chinese DSL label
  `上海菜` or the undefined `people_number` variable.
- Phase 2 required no query changes. `human1000` contains natural-language-only
  requests and therefore has no Oracle DSL to canonicalize.

## Formal Validation

For the 604 corrected Phase 1 records, generated validation plans were passed
through the complete current evaluator:

| Metric | Result |
|---|---:|
| Schema + commonsense + hard constraints | 604 / 604 |
| MicEPR | 100.0 |
| MacEPR | 100.0 |
| C-LPR | 100.0 |
| FPR | 100.0 |

## Companion Sandbox

The companion `ChinaTravel-Sandbox` release `2026.08.2` includes two related
data corrections:

- restores the canonical Chinese value `本帮菜` for all 66 corresponding
  Shanghai restaurant rows; the English value remains `Shanghai cuisine`;
- extends the closing time of `富春山居号游船` / `Fuchun Mountain Residence
  Cruise` from `15:00` to `18:00`, making its operating interval consistent
  with the published 3.0–3.5 hour recommended visit duration.

Use the corrected queries and sandbox together. Release `2026.08.1` briefly
used `上海菜` as the Chinese executable value; `2026.08.2` corrects that policy
without rewriting any natural-language query.
