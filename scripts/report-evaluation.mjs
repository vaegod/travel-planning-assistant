import assert from "node:assert/strict";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createHash } from "node:crypto";
const read = async (file) => JSON.parse(await readFile(file, "utf8"));
const development = await read(".runtime/eval-fixture-development.json"),
  holdout = await read(".runtime/eval-fixture-holdout.json"),
  live = await read(".runtime/eval-live-holdout.json");
assert.equal(development.datasetSha256, holdout.datasetSha256);
assert.equal(holdout.datasetSha256, live.datasetSha256);
const calls = live.results.flatMap((r) => r.modelCalls),
  turns = live.results.flatMap((r) => r.turns || []);
const percentile = (values, p) =>
  values.length
    ? [...values].sort((a, b) => a - b)[Math.ceil(values.length * p) - 1]
    : null;
const datasetText = await readFile("eval/cases.json", "utf8");
assert.equal(
  createHash("sha256").update(datasetText).digest("hex"),
  live.datasetSha256,
);
const evidence = {
  at: new Date().toISOString(),
  datasetSha256: live.datasetSha256,
  fixtureSha256: createHash("sha256")
    .update(await readFile("eval/fixtures/places.json"))
    .digest("hex"),
  artifactSha256: createHash("sha256")
    .update(
      await readFile(
        "travel-java-server/target/travel-java-server-0.0.1-SNAPSHOT.jar",
      ),
    )
    .digest("hex"),
  development,
  holdout,
  live,
};
await mkdir("docs", { recursive: true });
await writeFile(
  "docs/阶段4评估数据.json",
  JSON.stringify(evidence, null, 2) + "\n",
);
const total = development.results.length + holdout.results.length,
  passed = [...development.results, ...holdout.results].filter(
    (r) => r.passed,
  ).length;
const tokenNotice =
  live.summary.usageKnownCalls === calls.length
    ? `${calls.length} 次模型调用均取得输入/输出用量；合计输入 ${live.summary.knownInputTokens}、输出 ${live.summary.knownOutputTokens} Token。`
    : `${live.summary.usageKnownCalls}/${calls.length} 次调用有完整用量，缺失部分记为未知。`;
const estimate = (
  (live.summary.knownInputTokens * 0.4 + live.summary.knownOutputTokens * 3.2) /
  1e6
).toFixed(6);
const rows = [...development.results, ...holdout.results]
  .map(
    (r) =>
      `| ${r.id} | ${r.name} | ${r.split} | ${r.passed ? "通过" : "失败"} |`,
  )
  .join("\n");
const liveRows = live.results
  .map(
    (r) =>
      `| ${r.id} | ${r.name} | ${r.passed ? "通过" : "失败"} | ${r.durationMs} |`,
  )
  .join("\n");
const text = `# 阶段 4 评估报告

日期：2026-10-18，Asia/Shanghai。固定集版本 travel-eval-v1，数据 SHA-256：\`${live.datasetSha256}\`。原始结果与输入/输出详见 [阶段4评估数据.json](阶段4评估数据.json)。

## 评估方法与边界

- 固定 40 个案例，其中开发集 32 个、留出集 8 个。先验证开发集，再执行留出集；本次留出案例通过后未为其修改提示词或评分规则。
- 工作流评估使用真实 Spring Boot/MySQL 测试库和 HTTP 替身；测系统行为、校验与失败处理，不能据此宣称模型效果达到100%。
- 真实模型评估使用同一留出子集、Qwen/Qwen3.5-35B-A3B、非思考模式；提示词版本 planning-select-v2 / travel-chat-v3。景点用录制的公开OSM数据、路线用固定HTTP替身，控制输入差异，**不是公共地图API可用性测试**。
- 行程客观检查：城市、天数、预算、地点去重、来源、金额合计、预算上限、局部修改范围、保存与历史版本。聊天用约束字段和事先固定的中文关键词/正则规则，检查预算保留、同行人记忆、缺信息询问、会话隔离。没有采用模型打分，也没有人工旅游可行性、路线最优性、开放时间或真实价格评估。
- 留出集只有8例，不能作为大样本成功率或通用模型排名。真实评估仅一次正式运行；不将先前GLM/Qwen不同参数、不同输入的数据拼成公平性能对比。

## 结果

| 项目 | 结果 |
| --- | --- |
| 替身开发集 | ${development.summary.passed}/${development.summary.total} |
| 替身留出集 | ${holdout.summary.passed}/${holdout.summary.total} |
| 替身工作流合计 | ${passed}/${total} |
| 真实模型留出集 | ${live.summary.passed}/${live.summary.total} |
| 真实模型调用数 | ${calls.length} |
| 真实对话请求数 | ${turns.length} |
| 真实对话首段P50 / P95 | ${percentile(
  turns.map((t) => t.firstContentMs),
  0.5,
)} / ${percentile(
  turns.map((t) => t.firstContentMs),
  0.95,
)} ms |
| 真实对话完整P50 / P95 | ${percentile(
  turns.map((t) => t.durationMs),
  0.5,
)} / ${percentile(
  turns.map((t) => t.durationMs),
  0.95,
)} ms |

这些耗时包含本机后端与本地中继，样本数有限，不能当成供应商SLA。

${tokenNotice} 供应商未返回的字段保持null，不补写0；没有完整用量的失败/取消请求不能估算为免费。

仅按未缓存输入0.4元、输出3.2元/百万Token的硅基流动中国站短上下文单价，对已知用量换算约 **${estimate} 元**。这是假设口径下的估算，不是账户账单，不包含其他探测、重试、历史阶段调用或未知用量；价格参考[硅基流动价格页](https://www.siliconflow.cn/pricing)。

## 固定案例明细

| ID | 场景 | 划分 | 结果 |
| --- | --- | --- | --- |
${rows}

## 真实模型留出案例

| ID | 场景 | 结果 | 全案例耗时ms |
| --- | --- | --- | --- |
${liveRows}

全案例耗时可能包含生成基线、保存、修改、多个聊天轮次及轮询，不等同于单次模型耗时。每次模型调用有独立requestId、promptVersion、status、首段/完整耗时和可获得的Token用量，详见JSON。

## 重现

在独立travel_ai_test库准备好、后端JAR已构建后，根目录依次运行：

\`\`\`powershell
node.exe scripts/evaluate.mjs --split=development
node.exe scripts/evaluate.mjs --split=holdout
# 主动调用真实模型，会消耗额度：
node.exe scripts/evaluate.mjs --live --split=holdout
node.exe scripts/report-evaluation.mjs
\`\`\`

常规CI只运行\`node scripts/evaluate.mjs\`，40例全部使用替身，不需要真实Key。脚本拒绝未显式配置travel_ai_test的测试库地址，不连接开发库；不清空已有数据。\`DB_TEST_URL\`、\`DB_USERNAME\`、\`DB_PASSWORD\`可来自本地忽略的后端.env或环境变量。真实模式需已有模型配置。
`;
await writeFile("docs/阶段4评估报告.md", text);
console.log(
  JSON.stringify({
    fixture: `${passed}/${total}`,
    live: `${live.summary.passed}/${live.summary.total}`,
    modelCalls: calls.length,
    inputTokens: live.summary.knownInputTokens,
    outputTokens: live.summary.knownOutputTokens,
    estimatedCny: estimate,
  }),
);
