// Export the completed, opt-in real run without exporting local account credentials.
import { readFile, writeFile } from "node:fs/promises";
const run = JSON.parse(await readFile(".runtime/phase5-rag-live.json", "utf8"));
if (!run.passed) throw Error("Only export a complete passing run");
await writeFile("docs/阶段5评估数据.json", JSON.stringify(run, null, 2) + "\n");
const ready = run.cases.filter((c) => c.rag.status === "READY");
const calls = run.cases.flatMap((c) => c.rag.answer.modelCalls);
const durations = ready.map((c) => c.durationMs).sort((a, b) => a - b);
const middle = Math.floor(durations.length / 2);
const median =
  durations.length % 2
    ? durations[middle]
    : (durations[middle - 1] + durations[middle]) / 2;
const lines = [
  "# 阶段5：直接回答与RAG对比评估",
  "",
  `运行时间：${run.at}；模型 ${run.model}；向量模型 ${run.embeddingModel}（1024维）；提示词 travel-chat-v3 / travel-rag-v1。`,
  "",
  `固定集：eval/rag-cases.json（${run.dataset}），SHA256：\`${run.datasetSha256}\`。9例均为本阶段功能验收集，没有独立留出集，不能推断泛化准确率。`,
  "",
  "## 方法",
  "",
  "20份杭州官方公开页的项目摘要，加2份明确标注虚构的用户笔记。每题先建立空白普通会话直接回答，再以同题进行RAG（城市/日期/Top-K=4，余弦阈值0.55）。普通会话不读取知识库；两者提示词和输出形式有差别，因此这是系统能力对比，不是严格控制变量的模型排名实验。",
  "",
  "READY要求所有预期关键词出现、每条结论有引用、引用编号属于返回来源、逐字摘录属于检索片段，并通过资料详情API再次定位chunkId。ABSTAINED要求无结论。关键词命中只作为辅助检查，不能代替事实、实体对应关系与语义支持核查。",
  "",
  `结果：${run.summary.passed}/${run.summary.total}通过；${ready.length}题有依据回答，${run.cases.length - ready.length}题拒答。${calls.length}次RAG生成调用；有依据问答端到端中位数${median}ms、最大${durations.at(-1)}ms。仅本机这一次请求样本，不是SLA。`,
  "",
  "| 用例 | 问题 | RAG状态 | 预期词命中：直接 / RAG | 端到端ms |",
  "| --- | --- | --- | --- | --- |",
  ...run.cases.map(
    (c) =>
      `| ${c.id} | ${c.question} | ${c.rag.status} | ${c.terms.length ? `${c.directTermMatches}/${c.terms.length} / ${c.rag.answer.claims.map((x) => x.text).join("").length ? c.terms.length : 0}/${c.terms.length}` : "拒答检查，不计关键词"} | ${c.durationMs} |`,
  ),
  "",
  "## 人工核对与有意义的差异",
  "",
  "- R01：直接回答把龙井路88号配给龙井馆区，并未给出翁家山268号；RAG明确双峰馆区→龙井路88号、龙井馆区→翁家山268号，和导入的官方页摘要一致。可见仅统计关键词会漏掉实体错配。",
  "- R02：直接回答列出三个馆区，遗漏杭州工艺美术博物馆；RAG按摘要列出工艺美术、刀剪剑、扇、伞四个馆区。",
  "- R03：直接回答采用宫殿/内城/外城/水利的另一种叙述；RAG采用UNESCO该资料的瑶山遗址区、谷口高坝区、平原低坝区、城址区四部分。这里只验证对所引资料的忠实性。",
  "- R04：直接回答说明无法访问个人笔记；RAG正确提取虚构的青瓷4729与梧桐阅读角，证明信息来自导入资料。这不是旅游事实样本。",
  "- R05：成都没有已导入资料，RAG拒答；R06：当天余票由实时意图规则提前拒答，不能计入向量召回成功率。",
  "- R07/R09：同一虚构旧活动，2026日期过滤后拒答，2025有效期内返回旧书房；R08地址与来源一致。",
  "",
  "逐条检查本次6个有依据回答，其结论均能由所附摘录支持。系统自动校验只保证引用编号与逐字摘录有效，并不自动证明结论语义被摘录蕴含；后续扩大数据集应加入独立人工评分、Recall@K和冲突资料测试。",
  "",
  "## 用量与复现",
  "",
  `本次RAG生成汇总：输入${calls.reduce((n, c) => n + (c.inputTokens ?? 0), 0)} Token、输出${calls.reduce((n, c) => n + (c.outputTokens ?? 0), 0)} Token；仅统计模型返回的生成用量，不含Embedding与直接回答，不计算或宣称完整费用。Embedding调用在后端embedding_call日志单独记录。`,
  "",
  "运行 `node scripts/verify-rag.mjs` 会创建本机验收账号、导入资料，并实际调用向量/生成服务；需要运行中的本机后端及可用Key。之后运行 `node scripts/report-rag.mjs` 导出报告和资料目录。测试凭据仅在忽略目录.runtime，报告不含密码、Cookie或Key。普通CI使用HTTP替身，不自动执行真实评估。",
  "",
  "完整输入、直接输出、RAG结果、引用快照与请求追踪见[阶段5评估数据](阶段5评估数据.json)。",
];
await writeFile("docs/阶段5评估报告.md", lines.join("\n") + "\n");
const docs = JSON.parse(
  await readFile(
    "travel-java-server/src/main/resources/knowledge/hangzhou.json",
    "utf8",
  ),
);
const catalog = [
  "# 旅游知识库资料清单",
  "",
  "来源核对日期：2026-10-18。内置20份杭州资料，对应20个不同官方URL；内容是项目整理摘要，不是官方网站逐字全文。问答中的“逐字摘录”定位本地摘要或用户导入文本，原页链接用于进一步核对。",
  "",
  "网页发布日期与本地导入时间分别保存；未标明发布日期的条目留空，不推测网页更新时间。资料未标有效期不代表实时有效；本项目不定期自动抓取或同步网页。",
  "",
  "| 编号 | 资料 / 官方原页 | 原页发布日期 |",
  "| --- | --- | --- |",
  ...docs.map(
    (d, i) =>
      `| ${String(i + 1).padStart(2, "0")} | [${d.title}](${d.sourceUrl}) | ${d.publishedOn || "未标明"} |`,
  ),
  "",
  "UNESCO三项世界遗产介绍来源为UNESCO World Heritage Centre，英文资料由项目转述为中文并压缩整理，保留归属与原页链接。其描述内容采用[CC-BY-SA IGO 3.0](https://creativecommons.org/licenses/by-sa/3.0/igo/)；相应派生摘要按同许可标注。其余政府公开页保留来源，不据此宣称拥有原页全文或图片的再分发权；内置集只保存简短事实摘要。",
  "",
  "正文版本以源码资源文件为准；本阶段未收集成都内置资料。用户可以导入杭州/成都自己的UTF-8 TXT或Markdown，来源类型始终为USER，链接可空，不能伪造官方来源。",
];
await writeFile("docs/旅游知识库资料清单.md", catalog.join("\n") + "\n");
console.log(
  `Exported ${run.cases.length} cases and ${docs.length} source entries`,
);
