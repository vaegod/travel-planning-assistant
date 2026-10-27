---
license: cc-by-4.0
language:
- zh
- en
tags:
- travel-planning
- language-agents
- benchmark
configs:
- config_name: attractions
  data_files:
  - split: zh
    path: data/zh/attractions.parquet
  - split: en
    path: data/en/attractions.parquet
- config_name: restaurants
  data_files:
  - split: zh
    path: data/zh/restaurants.parquet
  - split: en
    path: data/en/restaurants.parquet
- config_name: accommodations
  data_files:
  - split: zh
    path: data/zh/accommodations.parquet
  - split: en
    path: data/en/accommodations.parquet
- config_name: trains
  data_files:
  - split: zh
    path: data/zh/trains.parquet
  - split: en
    path: data/en/trains.parquet
- config_name: flights
  data_files:
  - split: zh
    path: data/zh/flights.parquet
  - split: en
    path: data/en/flights.parquet
- config_name: poi
  data_files:
  - split: zh
    path: data/zh/poi.parquet
  - split: en
    path: data/en/poi.parquet
- config_name: subway_stations
  data_files:
  - split: zh
    path: data/zh/subway_stations.parquet
  - split: en
    path: data/en/subway_stations.parquet
---

# ChinaTravel Sandbox Environment Database

This dataset is licensed under [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/).

[English](#english) | [简体中文](#简体中文)

Release version: `2026.08.2`

## English

This dataset contains the bilingual static sandbox used by
[ChinaTravel](https://github.com/LAMDA-NeSy/ChinaTravel). It is a companion to
the [ChinaTravel query dataset](https://huggingface.co/datasets/LAMDA-NeSy/ChinaTravel)
and an artifact of the
[ChinaTravel paper](https://huggingface.co/papers/2412.13682).

The raw ZIP snapshots preserve the exact directory layout expected by the
ChinaTravel evaluator. Viewer-friendly Parquet configs provide normalized
tables for exploration and analysis. The raw snapshots remain the source of
truth for benchmark execution.

### Load normalized tables

```python
from datasets import load_dataset

attractions_en = load_dataset(
    "LAMDA-NeSy/ChinaTravel-Sandbox",
    "attractions",
    split="en",
)
trains_zh = load_dataset(
    "LAMDA-NeSy/ChinaTravel-Sandbox",
    "trains",
    split="zh",
)
```

### Table sizes

| Config | Chinese rows | English rows |
| --- | ---: | ---: |
| `attractions` | 3413 | 3413 |
| `restaurants` | 4655 | 4655 |
| `accommodations` | 3866 | 3866 |
| `trains` | 5345 | 5345 |
| `flights` | 720 | 720 |
| `poi` | 12172 | 12172 |
| `subway_stations` | 3635 | 3635 |

### Use with ChinaTravel

Download and extract one raw archive:

- `raw/ChinaTravel_sandbox_zh.zip` contains `database/`.
- `raw/ChinaTravel_sandbox_en.zip` contains `database_en/`.

Place the extracted directory under `chinatravel/environment/`. The English
snapshot uses canonical concept labels. Its exact changes are documented in
`manifests/ENGLISH_FIXES.md` and `manifests/english_manifest.json`.

Per-source-file checksums and a release manifest are included under
`manifests/` and at the repository root.

### Data quality policy in 2026.08.2

- The canonical Chinese executable cuisine value for the 66 corresponding
  Shanghai restaurant rows is `本帮菜`; the English value remains
  `Shanghai cuisine`.
- Natural-language queries may use varied expressions such as “上海菜”,
  “本帮菜”, or “上海特色美食”. Only executable DSL values are canonicalized.
- The corrected `18:00` closing time for `富春山居号游船` / `Fuchun Mountain
  Residence Cruise` is retained.

Release `2026.08.1` briefly used `上海菜` as the Chinese executable value.
The exact correction and validation boundary are documented in `manifests/`.

## 简体中文

本数据集包含 ChinaTravel 使用的中英文静态沙盒，是
[ChinaTravel Query 数据集](https://huggingface.co/datasets/LAMDA-NeSy/ChinaTravel)
的配套资源，也是
[ChinaTravel 论文](https://huggingface.co/papers/2412.13682)的关联产物。

`raw/` 中的 ZIP 保留测评代码所需的原始目录结构，是正式运行基准时的权威数据；
Parquet config 用于 Hub viewer、检索和统计分析。

```python
from datasets import load_dataset

restaurants_zh = load_dataset(
    "LAMDA-NeSy/ChinaTravel-Sandbox",
    "restaurants",
    split="zh",
)
poi_en = load_dataset(
    "LAMDA-NeSy/ChinaTravel-Sandbox",
    "poi",
    split="en",
)
```

解压中文包后得到 `database/`，解压英文包后得到 `database_en/`，将对应目录放到
`chinatravel/environment/` 即可。英文版本已统一概念标签，具体修改和校验信息见
`manifests/`。

`2026.08.2` 将上海餐馆的中文可执行菜系规范值恢复为“本帮菜”，英文对应值仍为
`Shanghai cuisine`。自然语言 Query 可以继续使用“上海菜”“本帮菜”“上海特色美食”
等多样化表达，只有 DSL 和沙盒内部值需要统一。上一版已修复的富春山居号游船
`18:00` 闭馆时间保持不变，精确变更记录见 `manifests/`。

## Citation

```bibtex
@inproceedings{shao2026chinatravel,
  title     = {ChinaTravel: An Open-Ended Travel Planning Benchmark with Compositional Constraint Validation for Language Agents},
  author    = {Jie-Jing Shao and Bo-Wen Zhang and Xiao-Wen Yang and Baizhi Chen and Siyu Han and Pang Jinghao and Wen-Da Wei and Guohao Cai and Zhenhua Dong and Lan-Zhe Guo and Yu-Feng Li},
  booktitle = {The Fourteenth International Conference on Learning Representations},
  year      = {2026},
  url       = {https://openreview.net/forum?id=0YRVlxY9BH}
}
```
