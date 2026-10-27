---
license: cc-by-4.0
configs:
- config_name: default
  data_files:
  - split: easy
    path: easy.csv
  - split: medium
    path: medium.csv
  - split: human
    path: human.csv
  - split: preference_base50
    path: preference_base50.csv
- config_name: test
  data_files:
  - split: human1000
    path: human1000.csv
- config_name: preference
  data_files:
  - split: preference0_base50
    path: preference0_base50.csv
  - split: preference1_base50
    path: preference1_base50.csv
  - split: preference2_base50
    path: preference2_base50.csv
  - split: preference3_base50
    path: preference3_base50.csv
  - split: preference4_base50
    path: preference4_base50.csv
  - split: preference5_base50
    path: preference5_base50.csv
- config_name: TPC2026_phase2
  data_files:
  - split: full
    path: phase2/part-*.csv
  - split: competition_test
    path: phase2/competition_test.csv
task_categories:
  - text-generation
language:
- zh
- en
---

# ChinaTravel Query Dataset

This dataset is licensed under [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/).

ChinaTravel is an open-ended travel-planning benchmark with compositional
constraint validation for language agents. See the
[paper](https://openreview.net/forum?id=0YRVlxY9BH),
[Hugging Face paper page](https://huggingface.co/papers/2412.13682),
[code](https://github.com/LAMDA-NeSy/ChinaTravel), and
[bilingual sandbox database](https://huggingface.co/datasets/LAMDA-NeSy/ChinaTravel-Sandbox)
([ModelScope mirror](https://modelscope.cn/datasets/Cbphcr/ChinaTravel-Sandbox))
for the complete benchmark resources.

## Introduction

For a given query, a language agent uses the sandbox tools to collect
information and produces a travel plan in JSON format. A plan contains daily
POIs, including restaurants, attractions, accommodations, and intercity
transport, together with inner-city transport routes.

The Phase 1 query records contain Chinese requests, English translations, and
executable constraint programs. The Phase 2 config contains 2,000 English
queries with their per-constraint English semantics and executable DSL. The
static POI and transport data are distributed separately in the companion
sandbox dataset linked above.

## Splits

- **Default / Easy**: 300 queries with at most one extra constraint.
- **Default / Medium**: 150 queries with complex constraints.
- **Default / Human**: 154 human-authored queries. These are more diverse and
  may contain constraints not seen in the easy and medium splits.
- **Default / Preference_base50**: 50 base queries used by the preference
  configuration.
- **Test / Human1000**: 1,000 test queries.
- **Preference / Preference0_base50**: Prefer more attractions.
- **Preference / Preference1_base50**: Prefer less inner-city transport time.
- **Preference / Preference2_base50**: Prefer less average transport time to
  restaurants.
- **Preference / Preference3_base50**: Prefer more spending on food.
- **Preference / Preference4_base50**: Prefer less spending on accommodation.
- **Preference / Preference5_base50**: Prefer a shorter distance to a specified
  POI.
- **TPC2026_phase2 / Full**: All 2,000 post-competition English queries covering
  the 53 published constraint templates.
- **TPC2026_phase2 / Competition_test**: The 100-query subset used in the
  official Phase 2 competition evaluation. The same rows are identified in
  `full` by `official_phase2_evaluation=true`. Phase 2 requires
  the latest companion `ChinaTravel-Sandbox`; legacy sandbox snapshots are not
  compatible with the canonical English entity and concept labels.


## Record Layout

- `uid`: unique query identifier.
- `tag`: query category.
- `start_city`: departure city.
- `target_city`: destination city.
- `days`: trip duration in days.
- `people_number`: number of travelers.
- `limit_rooms`: whether the query limits the number of rooms.
- `limits_room_type`: whether the query limits room types.
- `hard_logic_py`: executable Python constraint programs.
- `nature_language`: Chinese natural-language request.
- `nature_language_en`: English translation of the request.

The keys below are only in preference config:

- `preference`: Chinese preference description.
- `preference_en`: English preference description.
- `preference_py`: executable Python preference program.

The Phase 2 config uses this public schema:

- `uid`, `tag`, `start_city`, `target_city`, `days`, `people_number`;
- `hard_logic_py`: ordered executable constraint programs;
- `hard_logic_nl`: one English semantic statement per DSL program;
- `nature_language`: the complete numbered English request;
- `constraint_keys`: stable template identifiers aligned with both constraint
  lists;
- `official_phase2_evaluation`: whether the record belongs to the 100-query
  competition evaluation subset.

Because this repository's existing configs use the CSV builder,
`hard_logic_py`, `hard_logic_nl`, and `constraint_keys` are JSON-encoded strings
in the loaded Phase 2 table. Parse them with `json.loads`; the encoding preserves
the ordered lists exactly.

```python
import json
from datasets import load_dataset

phase2 = load_dataset("LAMDA-NeSy/ChinaTravel", "TPC2026_phase2", split="full")
competition_test = load_dataset(
    "LAMDA-NeSy/ChinaTravel", "TPC2026_phase2", split="competition_test"
)
record = phase2[0]
hard_logic_py = json.loads(record["hard_logic_py"])
hard_logic_nl = json.loads(record["hard_logic_nl"])
constraint_keys = json.loads(record["constraint_keys"])
```

Seed plans, source-plan paths, and generation traces are not included. See the
[Phase 2 release notes](PHASE2_RELEASE_NOTES.md) and the machine-readable
[audit report](phase2_audit_report.json) for validation and cleanup details.

## Data Maintenance

On 2026-07-21, we applied two corrections reported in Hugging Face
[Discussion #2](https://huggingface.co/datasets/LAMDA-NeSy/ChinaTravel/discussions/2)
and
[Discussion #3](https://huggingface.co/datasets/LAMDA-NeSy/ChinaTravel/discussions/3):

- aligned the trip duration and English text of one `easy` Chengdu-Wuhan query;
- replaced the unavailable Suzhou-Beijing airplane requirement in one `human`
  query with the available train requirement, consistently across its text and
  constraint program.

The fixes are contained in Hub commit `802b18d`. No other query rows were
changed by that cleanup.

On 2026-08-18, we added the complete 2,000-query Phase 2 config after a
post-competition audit. Natural-language wording was clarified without changing
the meaning of the executable constraints, and logically redundant OR clauses
were removed only when an identical branch was already independently required
by the same query. Details and checksums are recorded in the linked release
notes and audit report.

On 2026-08-20, we corrected 28 Phase 1 records whose executable constraints
were inconsistent with the query metadata or the companion sandbox inventory.
The corrections cover canonical entity/concept labels, unsupported or
contradictory categories, infeasible room-type and budget combinations, three
undefined DSL variables, and one city mismatch. The canonical Shanghai cuisine
label was also propagated to the same base query in all seven preference
splits. No Phase 2 or `human1000` record required a corresponding change.

All 604 corrected Phase 1 records have a plan that passes the current schema,
commonsense, and hard-constraint evaluator. The exact affected UIDs, fields,
file checksums, and full public-split audit are recorded in
[`DATA_QUALITY_REPAIRS_2026_08.json`](DATA_QUALITY_REPAIRS_2026_08.json) and
summarized in
[`DATA_QUALITY_FIXES_2026_08.md`](DATA_QUALITY_FIXES_2026_08.md). Use these
queries with release `2026.08.1` or later of the companion sandbox dataset.

## Citation

If our paper or related resources prove valuable to your research, we kindly ask for citation. Please feel free to contact us with any inquiries.

```bibtex
@inproceedings{shao2026chinatravel,
  title     = {ChinaTravel: An Open-Ended Travel Planning Benchmark with Compositional Constraint Validation for Language Agents},
  author    = {Jie-Jing Shao and Bo-Wen Zhang and Xiao-Wen Yang and Baizhi Chen and Siyu Han and Pang Jinghao and Wen-Da Wei and Guohao Cai and Zhenhua Dong and Lan-Zhe Guo and Yu-Feng Li},
  booktitle = {The Fourteenth International Conference on Learning Representations},
  year      = {2026},
  url       = {https://openreview.net/forum?id=0YRVlxY9BH}
}
```
