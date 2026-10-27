# 离线故障注入证据

本记录来自本地模拟HTTP服务，不调用真实模型，不计入40条正式质量成绩。

AgentModelTransportTests共8项通过，覆盖断连、超时、响应体中断、并发HTTP/2独立恢复、取消、一次/轮数上限、不可重试错误及剩余预算。

| 场景 | 物理请求 | 额外恢复请求 | 恢复前失败等待/毫秒 | 恢复请求耗时/毫秒 |
| --- | ---: | ---: | ---: | ---: |
| concurrent-test | 2 | 1 | 16 | 5 |
| concurrent-test | 2 | 1 | 18 | 3 |
| transport-test | 2 | 1 | 251 | 1 |
| body-loss-test | 2 | 1 | 1 | 1 |
| travel-agent-v7-recovery-ticket-constraints | 3 | 1 | 1 | 1 |

5个注入任务均在一次恢复后完成，恢复请求均未复用连接。完整真实工具循环测试同时断言estimate_budget与validate_itinerary各执行一次，恢复前后请求体相同。

预检源码的隔离HTTP/2反例测试按预期失败，检测到恢复请求复用正在使用的连接。最终实现同一并发测试通过；响应体读完后才清理独立连接池。

本地毫秒耗时只证明故障与计数链路，不代表真实模型恢复耗时。真实批次首次/含恢复成功率、额外请求和耗时只由该批次原始记录计算。诊断字段和日志哈希见[fault-injection.json](fault-injection.json)。
