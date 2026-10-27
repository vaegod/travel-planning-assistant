# 旅游规划 Agent 适配评测报告

运行：pilot-live-v3；阶段：pilot；模式：live；模型：Qwen/Qwen3.5-35B-A3B。

## 评测口径

- 基于ChinaTravel改编的单人目的地内任务，非ChinaTravel官方成绩；原题ID和改动保存在数据集。
- 两组使用相同模型、temperature、思考配置、输出上限和固定参考事实；Agent保留现有生产提示词、工具及后端规范化，一次生成组无工具、无修复。比较的是应用流程，不是两个不同模型。
- 地点来自固定的ChinaTravel景点子集；路段距离/时间/费用由明确的离线公式模拟，不评估实时高德可用性、真实票价、营业时间或日期安排。
- 每条每组一次正式尝试，不因评分失败重试；单次调用的失败及用量缺失均保留。对照同时运行，最多2个任务对、4个模型请求并发。
- 主指标为应完成任务全部硬约束通过率；不可行预算和无公交方案另统计正确拒绝，通用报错不算正确拒绝。评分独立于应用AgentRules。
- 这是小样本单次运行，无统计显著性或真实用户收益结论。Token仅列供应商返回的已知用量，未绑定价目表，不估算金额。

## 全部用例

| 指标 | 一次生成 | 工具调用 Agent |
| --- | --- | --- |
| 应完成任务通过 | 7/10（70.0%） | 8/10（80.0%） |
| 应拒绝任务正确拒绝 | 0/0（—） | 0/0（—） |
| 负例无行程交付 | 0/0 | 0/0 |
| 基础设施错误 | 1 | 1 |
| 耗时P50（全部尝试） | 11.32s | 17.91s |
| 耗时P95（全部尝试） | 122.02s | 148.72s |
| 模型调用总数 | 10 | 39 |
| 平均模型调用次数 | 1.00 | 3.90 |
| 工具调用总数 | 0 | 29 |
| 输入Token（已知部分） | 24008 | 160820 |
| 输出Token（已知部分） | 4152 | 7537 |
| 用量完整的调用数 | 9/10 | 38/39 |
| 地点可落到固定数据 | 100.0% | 100.0% |
| 正确路线元数据覆盖率 | 0.0% | 0.0% |

## 调试集

| 指标 | 一次生成 | 工具调用 Agent |
| --- | --- | --- |
| 应完成任务通过 | 7/10（70.0%） | 8/10（80.0%） |
| 应拒绝任务正确拒绝 | 0/0（—） | 0/0（—） |
| 负例无行程交付 | 0/0 | 0/0 |
| 基础设施错误 | 1 | 1 |
| 耗时P50（全部尝试） | 11.32s | 17.91s |
| 耗时P95（全部尝试） | 122.02s | 148.72s |
| 模型调用总数 | 10 | 39 |
| 平均模型调用次数 | 1.00 | 3.90 |
| 工具调用总数 | 0 | 29 |
| 输入Token（已知部分） | 24008 | 160820 |
| 输出Token（已知部分） | 4152 | 7537 |
| 用量完整的调用数 | 9/10 | 38/39 |
| 地点可落到固定数据 | 100.0% | 100.0% |
| 正确路线元数据覆盖率 | 0.0% | 0.0% |

## 留出集

| 指标 | 一次生成 | 工具调用 Agent |
| --- | --- | --- |
| 应完成任务通过 | 0/0（—） | 0/0（—） |
| 应拒绝任务正确拒绝 | 0/0（—） | 0/0（—） |
| 负例无行程交付 | 0/0 | 0/0 |
| 基础设施错误 | 0 | 0 |
| 耗时P50（全部尝试） | — | — |
| 耗时P95（全部尝试） | — | — |
| 模型调用总数 | 0 | 0 |
| 平均模型调用次数 | — | — |
| 工具调用总数 | 0 | 0 |
| 输入Token（已知部分） | 未知 | 未知 |
| 输出Token（已知部分） | 未知 | 未知 |
| 用量完整的调用数 | 0/0 | 0/0 |
| 地点可落到固定数据 | — | — |
| 正确路线元数据覆盖率 | — | — |

## 按任务类型

| 类型 | 一次生成 | Agent |
| --- | --- | --- |
| 新建行程 | 7/10 | 8/10 |
| 局部修改 | 0/0 | 0/0 |
| 明确不可行 | 0/0 | 0/0 |

## 失败归因

| 检查项 | 一次生成失败数 | Agent失败数 |
| --- | ---: | ---: |
| visitAndTravelValues | 2 | 1 |
| dailyTimeUsingReferenceRoutes | 2 | 1 |
| ticketSum | 2 | 1 |
| deliveredPlan | 1 | 1 |
| identity | 1 | 1 |
| travelMode | 1 | 1 |
| completeSchedule | 1 | 1 |
| groundedPlaces | 1 | 1 |
| distinctPlaces | 1 | 1 |
| attractionsPerDay | 1 | 1 |
| routeAvailable | 1 | 1 |
| reportedRouteConsistent | 1 | 1 |
| travelNotUnderestimated | 1 | 2 |
| ticketReferenceCoverage | 1 | 1 |
| budgetValid | 1 | 1 |
| minimumExpenses | 1 | 1 |
| transportReferenceCoverage | 1 | 1 |

检查项可重叠；没有交付行程的正例会同时不满足多个检查，不能视为多个独立事实错误。summary.json另列仅已交付行程的失败归因与接口错误码统计。

## 每条结果

| ID | 集合 | 组 | 结果 | 耗时 | 失败检查项 |
| --- | --- | --- | --- | --- | --- |
| CT01 | development | 工具调用 Agent | 通过 | 22.02s | — |
| CT01 | development | 一次生成 | 基础设施错误 | 122.02s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage |
| CT02 | development | 工具调用 Agent | 基础设施错误 | 148.72s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage |
| CT02 | development | 一次生成 | 通过 | 11.39s | — |
| CT03 | development | 工具调用 Agent | 通过 | 30.47s | — |
| CT03 | development | 一次生成 | 未通过 | 10.94s | ticketSum |
| CT04 | development | 工具调用 Agent | 通过 | 44.21s | — |
| CT04 | development | 一次生成 | 未通过 | 11.32s | visitAndTravelValues, dailyTimeUsingReferenceRoutes |
| CT05 | development | 工具调用 Agent | 通过 | 12.61s | — |
| CT05 | development | 一次生成 | 通过 | 11.29s | — |
| CT06 | development | 工具调用 Agent | 通过 | 13.20s | — |
| CT06 | development | 一次生成 | 通过 | 10.69s | — |
| CT07 | development | 工具调用 Agent | 通过 | 17.91s | — |
| CT07 | development | 一次生成 | 通过 | 15.68s | — |
| CT08 | development | 工具调用 Agent | 未通过 | 48.42s | travelNotUnderestimated |
| CT08 | development | 一次生成 | 通过 | 15.90s | — |
| CT09 | development | 工具调用 Agent | 通过 | 14.15s | — |
| CT09 | development | 一次生成 | 通过 | 10.87s | — |
| CT10 | development | 工具调用 Agent | 通过 | 15.26s | — |
| CT10 | development | 一次生成 | 通过 | 13.86s | — |

## 复现信息

- 数据SHA256：0b17e2f63cf1a9e9164dd4585a03f97527773c7cc8b7186a3ca0459b7aae860c
- 固定环境SHA256：e2b055c1fb9b3fb9a484df21d1d63983e3a6a7fdd5f05e48af7f059a35e717d1
- 协议/代码指纹：3df53ecf5e2d4066dd9238c4d5157a2d9c0c00760ef71d6150c056c383eda271
- 原始逐条模型输入、输出、工具轨迹及独立评分见同目录results.json；模型密钥与账号不进入报告。
