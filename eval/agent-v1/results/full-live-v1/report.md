# 旅游规划 Agent 适配评测报告

运行：full-live-v1；阶段：full；模式：live；模型：Qwen/Qwen3.5-35B-A3B。

## 评测口径

- 基于ChinaTravel改编的单人目的地内任务，非ChinaTravel官方成绩；原题ID和改动保存在数据集。
- 两组使用相同模型、temperature、思考配置、输出上限和固定参考事实；Agent保留现有生产提示词、工具及后端规范化，一次生成组无工具、无修复。比较的是应用流程，不是两个不同模型。
- 地点来自固定的ChinaTravel景点子集；路段距离/时间/费用由明确的离线公式模拟，不评估实时高德可用性、真实票价、营业时间或日期安排。
- 每条每组一次正式尝试，不因评分失败重试；单次调用的失败及用量缺失均保留。对照同时运行，最多2个任务对、4个模型请求并发。
- 主指标为应完成任务的独立规则全部通过率；不可行预算和无公交方案另统计正确拒绝，通用报错不算正确拒绝。评分独立于应用AgentRules。
- 这是小样本单次运行，无统计显著性或真实用户收益结论。Token仅列供应商返回的已知用量，未绑定价目表，不估算金额。

## 全部用例

| 指标 | 一次生成 | 工具调用 Agent |
| --- | --- | --- |
| 应完成任务规则全部通过 | 10/38（26.3%） | 12/38（31.6%） |
| 应拒绝任务正确拒绝 | 0/2（0.0%） | 0/2（0.0%） |
| 负例无行程交付 | 2/2 | 2/2 |
| 模型拒绝但接口未明确呈现 | 0 | 0 |
| 基础设施错误 | 12 | 16 |
| 耗时P50（全部尝试） | 13.86s | 17.91s |
| 耗时P95（全部尝试） | 46.75s | 117.78s |
| 尝试的模型轮数 | 40 | 127 |
| 已捕获模型请求数 | 40 | 126 |
| 平均模型调用次数 | 1.00 | 3.15 |
| 工具调用总数 | 0 | 91 |
| 模型输出截断次数 | 0 | 0 |
| 输入Token（已知部分） | 76047 | 509194 |
| 输出Token（已知部分） | 20971 | 34742 |
| 用量完整的调用数 | 28/40 | 111/126 |
| 地点可落到固定数据 | 100.0% | 100.0% |
| 正确路线元数据覆盖率 | 3.7% | 0.0% |

## 调试集

| 指标 | 一次生成 | 工具调用 Agent |
| --- | --- | --- |
| 应完成任务规则全部通过 | 8/30（26.7%） | 12/30（40.0%） |
| 应拒绝任务正确拒绝 | 0/0（—） | 0/0（—） |
| 负例无行程交付 | 0/0 | 0/0 |
| 模型拒绝但接口未明确呈现 | 0 | 0 |
| 基础设施错误 | 6 | 7 |
| 耗时P50（全部尝试） | 15.68s | 22.02s |
| 耗时P95（全部尝试） | 48.22s | 148.72s |
| 尝试的模型轮数 | 30 | 108 |
| 已捕获模型请求数 | 30 | 108 |
| 平均模型调用次数 | 1.00 | 3.60 |
| 工具调用总数 | 0 | 81 |
| 模型输出截断次数 | 0 | 0 |
| 输入Token（已知部分） | 65308 | 460776 |
| 输出Token（已知部分） | 16339 | 29250 |
| 用量完整的调用数 | 24/30 | 101/108 |
| 地点可落到固定数据 | 100.0% | 100.0% |
| 正确路线元数据覆盖率 | 4.8% | 0.0% |

## 留出集

| 指标 | 一次生成 | 工具调用 Agent |
| --- | --- | --- |
| 应完成任务规则全部通过 | 2/8（25.0%） | 0/8（0.0%） |
| 应拒绝任务正确拒绝 | 0/2（0.0%） | 0/2（0.0%） |
| 负例无行程交付 | 2/2 | 2/2 |
| 模型拒绝但接口未明确呈现 | 0 | 0 |
| 基础设施错误 | 6 | 9 |
| 耗时P50（全部尝试） | 0.06s | 0.54s |
| 耗时P95（全部尝试） | 41.54s | 117.78s |
| 尝试的模型轮数 | 10 | 19 |
| 已捕获模型请求数 | 10 | 18 |
| 平均模型调用次数 | 1.00 | 1.80 |
| 工具调用总数 | 0 | 10 |
| 模型输出截断次数 | 0 | 0 |
| 输入Token（已知部分） | 10739 | 48418 |
| 输出Token（已知部分） | 4632 | 5492 |
| 用量完整的调用数 | 4/10 | 10/18 |
| 地点可落到固定数据 | 100.0% | — |
| 正确路线元数据覆盖率 | 0.0% | — |

## 按任务类型

| 类型 | 一次生成 | Agent |
| --- | --- | --- |
| 新建行程 | 10/30 | 12/30 |
| 局部修改 | 0/8 | 0/8 |
| 明确不可行 | 0/2 | 0/2 |

## 失败归因

| 检查项 | 一次生成失败数 | Agent失败数 |
| --- | ---: | ---: |
| travelNotUnderestimated | 20 | 26 |
| minimumExpenses | 17 | 19 |
| ticketSum | 14 | 19 |
| visitAndTravelValues | 12 | 19 |
| dailyTimeUsingReferenceRoutes | 12 | 19 |
| completeSchedule | 11 | 19 |
| distinctPlaces | 11 | 19 |
| attractionsPerDay | 11 | 19 |
| routeAvailable | 11 | 19 |
| reportedRouteConsistent | 11 | 19 |
| ticketReferenceCoverage | 11 | 19 |
| budgetValid | 11 | 19 |
| transportReferenceCoverage | 11 | 19 |
| deliveredPlan | 10 | 19 |
| identity | 10 | 19 |
| travelMode | 10 | 19 |
| groundedPlaces | 10 | 19 |
| requiredPlace | 8 | 8 |
| revisionScope | 8 | 8 |
| requiredCategory | 2 | 10 |
| expected_explicit_refusal | 2 | 2 |

检查项可重叠；没有交付行程的正例会同时不满足多个检查，不能视为多个独立事实错误。summary.json另列仅已交付行程的失败归因与接口错误码统计。

## 每条结果

| ID | 集合 | 组 | 结果 | 耗时 | 失败检查项 |
| --- | --- | --- | --- | --- | --- |
| B01 | development | 工具调用 Agent | 基础设施错误 | 0.63s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B01 | development | 一次生成 | 基础设施错误 | 0.05s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B02 | development | 工具调用 Agent | 基础设施错误 | 0.56s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B02 | development | 一次生成 | 基础设施错误 | 0.05s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B03 | development | 工具调用 Agent | 基础设施错误 | 0.59s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B03 | development | 一次生成 | 基础设施错误 | 0.05s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B04 | development | 工具调用 Agent | 基础设施错误 | 3.73s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B04 | development | 一次生成 | 未通过 | 4.71s | ticketSum, requiredPlace, revisionScope |
| B05 | development | 工具调用 Agent | 基础设施错误 | 0.57s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B05 | development | 一次生成 | 基础设施错误 | 0.07s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B06 | development | 工具调用 Agent | 基础设施错误 | 0.56s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B06 | development | 一次生成 | 基础设施错误 | 0.06s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B07 | holdout | 工具调用 Agent | 基础设施错误 | 0.57s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B07 | holdout | 一次生成 | 基础设施错误 | 0.06s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B08 | holdout | 工具调用 Agent | 基础设施错误 | 0.57s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B08 | holdout | 一次生成 | 基础设施错误 | 0.06s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredPlace, revisionScope |
| B09 | holdout | 工具调用 Agent | 基础设施错误 | 0.54s | expected_explicit_refusal |
| B09 | holdout | 一次生成 | 基础设施错误 | 0.05s | expected_explicit_refusal |
| B10 | holdout | 工具调用 Agent | 基础设施错误 | 0.54s | expected_explicit_refusal |
| B10 | holdout | 一次生成 | 基础设施错误 | 0.04s | expected_explicit_refusal |
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
| CT11 | development | 工具调用 Agent | 未通过 | 99.06s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT11 | development | 一次生成 | 未通过 | 29.61s | minimumExpenses |
| CT12 | development | 工具调用 Agent | 通过 | 50.32s | — |
| CT12 | development | 一次生成 | 未通过 | 30.99s | minimumExpenses |
| CT13 | development | 工具调用 Agent | 未通过 | 28.74s | travelNotUnderestimated |
| CT13 | development | 一次生成 | 未通过 | 6.56s | completeSchedule, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage |
| CT14 | development | 工具调用 Agent | 通过 | 23.62s | — |
| CT14 | development | 一次生成 | 未通过 | 18.29s | travelNotUnderestimated, minimumExpenses |
| CT15 | development | 工具调用 Agent | 未通过 | 67.01s | travelNotUnderestimated |
| CT15 | development | 一次生成 | 未通过 | 21.64s | minimumExpenses |
| CT16 | development | 工具调用 Agent | 未通过 | 35.61s | travelNotUnderestimated |
| CT16 | development | 一次生成 | 未通过 | 17.75s | travelNotUnderestimated |
| CT17 | development | 工具调用 Agent | 通过 | 43.92s | — |
| CT17 | development | 一次生成 | 未通过 | 19.52s | minimumExpenses |
| CT18 | development | 工具调用 Agent | 未通过 | 68.14s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT18 | development | 一次生成 | 未通过 | 16.38s | travelNotUnderestimated |
| CT19 | development | 工具调用 Agent | 未通过 | 20.50s | travelNotUnderestimated |
| CT19 | development | 一次生成 | 未通过 | 18.60s | travelNotUnderestimated |
| CT20 | development | 工具调用 Agent | 通过 | 20.45s | — |
| CT20 | development | 一次生成 | 未通过 | 16.91s | ticketSum, minimumExpenses |
| CT21 | development | 工具调用 Agent | 未通过 | 116.80s | travelNotUnderestimated |
| CT21 | development | 一次生成 | 通过 | 39.62s | — |
| CT22 | development | 工具调用 Agent | 未通过 | 160.94s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT22 | development | 一次生成 | 未通过 | 28.37s | travelNotUnderestimated, minimumExpenses |
| CT23 | development | 工具调用 Agent | 未通过 | 16.69s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT23 | development | 一次生成 | 未通过 | 46.75s | travelNotUnderestimated |
| CT24 | development | 工具调用 Agent | 未通过 | 39.35s | travelNotUnderestimated |
| CT24 | development | 一次生成 | 未通过 | 48.22s | travelNotUnderestimated, transportReferenceCoverage |
| CT25 | holdout | 工具调用 Agent | 未通过 | 117.78s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT25 | holdout | 一次生成 | 未通过 | 26.97s | travelNotUnderestimated, budgetValid |
| CT26 | holdout | 工具调用 Agent | 基础设施错误 | 0.53s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT26 | holdout | 一次生成 | 通过 | 28.30s | — |
| CT27 | holdout | 工具调用 Agent | 基础设施错误 | 32.06s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT27 | holdout | 一次生成 | 通过 | 30.28s | — |
| CT28 | holdout | 工具调用 Agent | 基础设施错误 | 0.54s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT28 | holdout | 一次生成 | 基础设施错误 | 0.05s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT29 | holdout | 工具调用 Agent | 基础设施错误 | 0.52s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT29 | holdout | 一次生成 | 基础设施错误 | 0.04s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT30 | holdout | 工具调用 Agent | 基础设施错误 | 5.28s | deliveredPlan, identity, travelMode, completeSchedule, groundedPlaces, distinctPlaces, attractionsPerDay, visitAndTravelValues, dailyTimeUsingReferenceRoutes, routeAvailable, reportedRouteConsistent, travelNotUnderestimated, ticketReferenceCoverage, budgetValid, ticketSum, minimumExpenses, transportReferenceCoverage, requiredCategory |
| CT30 | holdout | 一次生成 | 未通过 | 41.54s | travelNotUnderestimated, ticketSum |

## 复现信息

- 数据SHA256：fc0d2c0c0b3d5d1184da62ccadfc4202d6ab1d559cd118a913477eb5f5fa2a78
- 固定环境SHA256：e2b055c1fb9b3fb9a484df21d1d63983e3a6a7fdd5f05e48af7f059a35e717d1
- 协议/代码指纹：3df53ecf5e2d4066dd9238c4d5157a2d9c0c00760ef71d6150c056c383eda271
- 原始逐条模型输入、输出、工具轨迹及独立评分见同目录results.json；模型密钥与账号不进入报告。
- 模型轮数包含连接失败的尝试；已捕获请求数只计到达本地转发器的请求，两者可能不同。未到达转发器的连接失败不虚构响应、用量或工具轨迹。
- 本轮复用此前相同用例/上下文/代码协议的20条尝试，保留原运行ID和数据集哈希，不重复计数。
