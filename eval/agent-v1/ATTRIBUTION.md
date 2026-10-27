# Data attribution and adaptation record

This is a project-specific adapted evaluation, not an official ChinaTravel leaderboard submission.

## Upstream resources

- ChinaTravel Query Dataset, LAMDA-NeSy / ChinaTravel authors.
  - Repository: https://huggingface.co/datasets/LAMDA-NeSy/ChinaTravel
  - Pinned revision: a7d893afb628af6ca181625b6c7cca2d60a6bccb
  - Downloaded: 2026-10-24.
- ChinaTravel-Sandbox Chinese data, LAMDA-NeSy / ChinaTravel authors.
  - Repository: https://huggingface.co/datasets/LAMDA-NeSy/ChinaTravel-Sandbox
  - Pinned revision: 885c7dad143bf040238ddca263f4788d582ee0f4
  - Downloaded: 2026-10-24.
- Code, original evaluation and citation: https://github.com/LAMDA-NeSy/ChinaTravel
- Both dataset resources: Creative Commons Attribution 4.0 International.
  License: https://creativecommons.org/licenses/by/4.0/

Original dataset cards are retained in source/README.md and source/sandbox-README.md.
The linked upstream release notes and audit files are included for reference; they were not used as evaluation inputs.
Raw CSV/parquet inputs, repository metadata and file SHA256s are retained for verification.
Original hard_logic_py text is stored as provenance data only and is never executed.

## Adaptations

Each case retains its source UID, complete original row, mapped dimensions and a Chinese changes list.
Destination and duration are preserved. Original single-person queries are preferred.
If an upstream request involves multiple people it is explicitly normalized to one person.
For the original 40-case set, a numeric budget is preserved with its scope changed to destination-only expenses;
if absent, a clearly declared budget of 1000 CNY/day is introduced. In the later 20-case set,
positive-task budgets are normalized to 1000 CNY/day as recorded in that dataset.
Departure city and intercity transport are excluded from adapted grading.
Unmapped restaurant, hotel-room and other constraints are excluded and remain visible in the source row.

The project adds three transport modes, two attractions per day, optional hotel rest, expense floors,
and for selected cases a required attraction category. These additions are not claimed as upstream labels.
Eight local-revision cases and two provably infeasible cases are project-authored.

Attractions are selected as dense eight-POI clusters using upstream coordinates, price and minimum-visit fields.
Ticket values are quantized to two decimal places. This snapshot is not a claim about current ticket prices.
Accommodation (200 CNY/night), food (80 CNY/day) and other expenses (20 CNY/day) are evaluation assumptions.
The route graph and transit lines/fares are explicitly synthetic geometry-based fixtures, not recorded Amap routes.
All map HTTP calls in the experiment go to an isolated loopback fixture; no live Amap calls are made.

## Interpretation

The comparison measures a one-shot workflow versus the current application Agent workflow under shared evidence.
It is not a pure model ranking: the Agent retains server normalization, bounded execution and tool feedback.
The evaluator is an independent Node module and does not import the application's AgentRules.
Semantic criteria and refusal criteria are recorded in each adapted case. The original holdout was later
used for debugging and is now part of the regression set; the separate 20-case set was not used for
project debugging before its first evaluation and is treated as exposed after that execution.
One attempt is retained for each case/group, including failed, truncated, timeout and rejected results.

