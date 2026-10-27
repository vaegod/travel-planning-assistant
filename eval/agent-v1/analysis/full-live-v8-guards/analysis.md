# 新建行程失败记录分析

来源：full-live-v8-guards；results.json SHA-256：67fb847ae9866901bbc72203db06a5d10840be99d0414be154fa05cbee29a80b。只读提取已保存的请求、工具反馈及最终状态，没有重新请求模型。

## 终止原因与过程证据

- AGENT_ROUND_LIMIT：1 条。
- MODEL_TIMEOUT：2 条。
- INDEPENDENT_RULE_FAILURE：1 条。
- AGENT_REPAIR_LIMIT：1 条。
- AGENT_NO_VALID_DRAFT：1 条。
- MODEL_CONNECTION_ERROR：1 条。

过程证据可以重叠；终止错误码不等同于最早的触发原因。

- mapRequestFailed：0 条。
- malformedNestedJson：0 条。
- restSlotFeedback：0 条。
- modelTimeout：2 条。
- taskTimeout：0 条。

| 用例 | 终止错误 | 捕获/尝试模型轮数 | 耗时/秒 | 显式查询/提交草稿 | 工具反馈 |
| --- | --- | ---: | ---: | ---: | --- |
| CT06 | AGENT_ROUND_LIMIT | 8/8 | 132.30 | 7/0 | deliveredPlan；identity；travelMode；completeSchedule；groundedPlaces；distinctPlaces；attractionsPerDay；visitAndTravelValues；dailyTimeUsingReferenceRoutes；routeAvailable；reportedRouteConsistent；travelNotUnderestimated；ticketReferenceCoverage；budgetValid；ticketSum；minimumExpenses；transportReferenceCoverage |
| CT09 | MODEL_TIMEOUT | 2/2 | 128.43 | 0/0 | deliveredPlan；identity；travelMode；completeSchedule；groundedPlaces；distinctPlaces；attractionsPerDay；visitAndTravelValues；dailyTimeUsingReferenceRoutes；routeAvailable；reportedRouteConsistent；travelNotUnderestimated；ticketReferenceCoverage；budgetValid；ticketSum；minimumExpenses；transportReferenceCoverage |
| CT14 | MODEL_TIMEOUT | 3/3 | 163.97 | 1/0 | deliveredPlan；identity；travelMode；completeSchedule；groundedPlaces；distinctPlaces；attractionsPerDay；visitAndTravelValues；dailyTimeUsingReferenceRoutes；routeAvailable；reportedRouteConsistent；travelNotUnderestimated；ticketReferenceCoverage；budgetValid；ticketSum；minimumExpenses；transportReferenceCoverage；requiredCategory |
| CT15 | 独立规则未通过 | 3/3 | 39.90 | 1/1 | 南京夫子庙步行街：120分钟<参考180分钟 |
| CT22 | AGENT_REPAIR_LIMIT | 7/7 | 169.01 | 1/5 | 无法唯一确认地点：悬空飞越剧场 (春熙路茂业百货店)；请使用地图返回的完整地点名称 |
| CT26 | AGENT_NO_VALID_DRAFT | 3/3 | 24.63 | 1/0 | deliveredPlan；identity；travelMode；completeSchedule；groundedPlaces；distinctPlaces；attractionsPerDay；visitAndTravelValues；dailyTimeUsingReferenceRoutes；routeAvailable；reportedRouteConsistent；travelNotUnderestimated；ticketReferenceCoverage；budgetValid；ticketSum；minimumExpenses；transportReferenceCoverage；requiredCategory |
| CT29 | MODEL_CONNECTION_ERROR | 0/1 | 0.53 | 0/0 | deliveredPlan；identity；travelMode；completeSchedule；groundedPlaces；distinctPlaces；attractionsPerDay；visitAndTravelValues；dailyTimeUsingReferenceRoutes；routeAvailable；reportedRouteConsistent；travelNotUnderestimated；ticketReferenceCoverage；budgetValid；ticketSum；minimumExpenses；transportReferenceCoverage；requiredCategory |

## 修复方向

1. 显式查询设置上限，并保留最后两轮用于草稿提交/修复；预算工具必须实际成功调用。模型仅返回文字说明时，进行有上限的工具补正，计入同一8轮预算。网络失败不自动重试。
2. 名称优先使用实际查询ID及精确名称，再做空白、全角ASCII字符的唯一格式匹配；保留分店、标点和词语信息。规范化后多ID冲突时要求明确ID，不根据简称猜测。
3. 游览时间区分通用15～360范围与调用方提供的具体景点下限；将已经共享的参考表映射为结构化请求约束，服务端持有、模型不能覆盖。校验报告具体字段、实际值、要求值，并按下限重算每日时间可行性。高德不被描述为提供这些下限。
4. 新建与局部修改都支持这些约束；未提供明确下限时保留通用检查及说明，局部修改保留未选历史活动。基础设施失败继续计入分母，捕获请求数不代表全部已尝试轮数。
5. 正式复测保持模型、数据、独立评分器、任务协议、轮数上限及节流配置一致；80 个尝试全部重新运行，基础设施失败照常计入。

本轮使用过的 40 条属于回归集，不是新盲测。真实高德服务准确性不在离线合成路线评测范围内。
