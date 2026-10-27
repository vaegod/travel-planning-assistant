import assert from "node:assert/strict";
import { readFile, writeFile, access } from "node:fs/promises";
import { resolve, dirname } from "node:path";
import {summarizeRecovery} from "./agent-eval/model-recovery.mjs";
const input=process.argv.find(x=>x.startsWith("--input="))?.slice(8);
assert(input,"--input=path/to/results.json is required");
const path=resolve(input),report=JSON.parse(await readFile(path,"utf8"));
try {
  await access(resolve(dirname(path),"INVALID_RUN.md"));
  throw Error("This run is marked INVALID_RUN.md; its results cannot be summarized as model-quality evidence");
}catch(error){if(error.code!=="ENOENT")throw error;}
const rows=report.results;
const percentile=(values,p)=>values.length?[...values].sort((a,b)=>a-b)[Math.ceil(values.length*p)-1]:null;
const fraction=(n,d)=>d?n/d:null;
function summarize(items){
  const positive=items.filter(x=>x.expectedOutcome==="completed"),negative=items.filter(x=>x.expectedOutcome==="refused");
  const calls=items.flatMap(x=>x.modelRecords);
  const known=calls.filter(x=>Number.isFinite(x.response?.usage?.prompt_tokens)&&Number.isFinite(x.response?.usage?.completion_tokens));
  const checks={};
  for(const item of positive)for(const [key,value]of Object.entries(item.score.checks)){
    checks[key]||={passed:0,total:0};checks[key].total++;if(value)checks[key].passed++;
  }
  const failures={};
  for(const item of items)for(const reason of item.score.failures)failures[reason]=(failures[reason]||0)+1;
  const deliveredFailures={},workflowFailures={};
  for(const item of items){
    if(item.score.outcome==="completed")for(const reason of item.score.failures)
      deliveredFailures[reason]=(deliveredFailures[reason]||0)+1;
    if(item.observation.error){
      const code=item.observation.error.code;
      workflowFailures[code]=(workflowFailures[code]||0)+1;
    }
  }
  const metricSum=key=>positive.reduce((sum,x)=>sum+(x.score.metrics[key]||0),0);
  return {
    caseCount:items.length,positiveCases:positive.length,negativeCases:negative.length,
    completedPositive:positive.filter(x=>x.score.outcome==="completed").length,
    positivePassed:positive.filter(x=>x.score.taskPassed).length,
    positiveSuccessRate:fraction(positive.filter(x=>x.score.taskPassed).length,positive.length),
    correctRefusals:negative.filter(x=>x.score.correctRefusal).length,
    correctRefusalRate:fraction(negative.filter(x=>x.score.correctRefusal).length,negative.length),
    safeNoPlanOnNegative:negative.filter(x=>x.score.safeNoPlan).length,
    hiddenRefusals:negative.filter(x=>x.score.modelRefusalObserved&&!x.score.correctRefusal).length,
    modelFreeBudgetRefusals:items.filter(x=>x.modelCalls===0&&x.observation.job?.refusal?.code==="BUDGET_BELOW_MINIMUM").length,
    infrastructureErrors:items.filter(x=>x.score.infrastructureError).length,
    latencyAllAttempts:{n:items.length,p50Ms:percentile(items.map(x=>x.durationMs),.5),p95Ms:percentile(items.map(x=>x.durationMs),.95)},
    modelCalls:items.reduce((sum,x)=>sum+x.modelCalls,0),
    attemptedModelRounds:items.reduce((sum,x)=>sum+(x.group==="agent"?(x.observation.job?.modelRounds??x.modelCalls):1),0),
    meanModelCalls:fraction(items.reduce((sum,x)=>sum+x.modelCalls,0),items.length),
    toolCalls:items.reduce((sum,x)=>sum+x.toolCalls,0),
    mapHttpRequests:items.reduce((sum,x)=>sum+(x.mapCalls?.length||0),0),
    queuedModelCalls:calls.filter(x=>x.queueWaitMs>0).length,
    totalQueueWaitMs:calls.reduce((sum,x)=>sum+(x.queueWaitMs||0),0),
    forwardedLatencyExcludingQueue:{n:calls.filter(x=>Number.isFinite(x.durationMs)).length,
      p50Ms:percentile(calls.filter(x=>Number.isFinite(x.durationMs)).map(x=>Math.max(0,x.durationMs-(x.queueWaitMs||0))),.5)},
    truncatedModelResponses:calls.filter(x=>x.response?.choices?.[0]?.finish_reason==="length").length,
    usageKnownCalls:known.length,totalUsageCalls:calls.length,
    knownInputTokens:known.length?known.reduce((n,x)=>n+x.response.usage.prompt_tokens,0):null,
    knownOutputTokens:known.length?known.reduce((n,x)=>n+x.response.usage.completion_tokens,0):null,
    groundingRate:fraction(metricSum("grounded"),metricSum("actualCount")),
    reportedRouteCoverage:fraction(metricSum("validReportedLegs"),metricSum("requestedLegs")),
    checks:Object.fromEntries(Object.entries(checks).map(([key,value])=>[key,{...value,rate:fraction(value.passed,value.total)}])),
    failureCounts:Object.fromEntries(Object.entries(failures).sort((a,b)=>b[1]-a[1])),
    failureCountsOnDelivered:deliveredFailures,workflowFailureCounts:workflowFailures,
    recovery:summarizeRecovery(items),positiveRecovery:summarizeRecovery(positive),
  };
}
const groups={};
for(const group of ["one_shot","agent"]){
  groups[group]={};
  for(const split of ["all","development","holdout"])
    groups[group][split]=summarize(rows.filter(x=>x.group===group&&(split==="all"||x.split===split)));
}
const scenarioGroups={};
const facets={
  plan:r=>r.kind==="plan"&&r.expectedOutcome==="completed",
  revise:r=>r.kind==="revise",
  refusal:r=>r.expectedOutcome==="refused",
};
for(const [name,predicate]of Object.entries(facets))
  scenarioGroups[name]=Object.fromEntries(["one_shot","agent"].map(group=>[group,summarize(rows.filter(r=>r.group===group&&predicate(r)))]));
const unique=new Set(rows.map(x=>x.caseId+":"+x.group));
assert.equal(unique.size,rows.length,"Duplicate case/group rows");
const expected=report.metadata.phase==="pilot"?20:80;
const digest={
  metadata:report.metadata,attempts:rows.length,expectedAttempts:expected,
  experimentCompleted:rows.length===expected,groups,scenarioGroups,
  comparison:{
    positiveSuccessDelta:groups.agent.all.positiveSuccessRate-groups.one_shot.all.positiveSuccessRate,
    correctRefusalDelta:groups.agent.all.correctRefusalRate===null?null:groups.agent.all.correctRefusalRate-groups.one_shot.all.correctRefusalRate,
    latencyP50DeltaMs:groups.agent.all.latencyAllAttempts.p50Ms-groups.one_shot.all.latencyAllAttempts.p50Ms,
  },
};
await writeFile(resolve(dirname(path),"summary.json"),JSON.stringify(digest,null,2)+"\n");
const pct=n=>n===null?"—":(100*n).toFixed(1)+"%";
const seconds=n=>n===null?"—":(n/1000).toFixed(2)+"s";
const headings={"one_shot":"一次生成","agent":"工具调用 Agent"};
const checkLabels={
 deliveredPlan:"交付有效草稿",identity:"城市、天数及预算上限一致",travelMode:"交通方式一致",
 completeSchedule:"每日时段完整",groundedPlaces:"地点匹配固定候选",distinctPlaces:"景点不重复",
 attractionsPerDay:"每天至少两个景点",visitAndTravelValues:"游览与交通数值符合参考下限",
 dailyTimeUsingReferenceRoutes:"每日总时长不超限",routeAvailable:"所选方式存在可用路线",
 reportedRouteConsistent:"路线记录与前后地点一致",travelNotUnderestimated:"交通时间没有低估",
 ticketReferenceCoverage:"门票预留覆盖参考费用",budgetValid:"费用格式正确且不超预算",
 ticketSum:"门票分项与时段费用合计一致",minimumExpenses:"住宿餐饮等覆盖约定下限",
 transportReferenceCoverage:"交通预算覆盖参考费用",requiredCategory:"包含指定类型景点",
 requiredPlace:"包含指定替换景点",revisionScope:"局部修改范围正确",expected_explicit_refusal:"向用户明确说明不可行原因",
};
function failureSummary(row){
  if(row.expectedOutcome==="refused"&&!row.score.correctRefusal&&row.observation.plan?.success===true)
    return "不可行任务仍交付行程";
  if(row.observation.error)return row.observation.error.code;
  if(row.score.checks.deliveredPlan===false)return "未交付有效草稿";
  return row.score.failures.map(r=>checkLabels[r]||r).join("；")||"—";
}
const metrics=[
 ["应完成任务规则全部通过",s=>s.positivePassed+"/"+s.positiveCases+"（"+pct(s.positiveSuccessRate)+"）"],
 ["应拒绝任务正确拒绝",s=>s.correctRefusals+"/"+s.negativeCases+"（"+pct(s.correctRefusalRate)+"）"],
 ["负例无行程交付",s=>s.safeNoPlanOnNegative+"/"+s.negativeCases],
 ["Java预算预检拒绝（0次模型）",s=>String(s.modelFreeBudgetRefusals)],
 ["末轮文本命中负例关键词（诊断）",s=>String(s.hiddenRefusals)],
 ["基础设施错误",s=>String(s.infrastructureErrors)],
 ["首次通过正例（未使用传输恢复）",s=>s.positiveRecovery.firstAttemptPassed+"/"+s.positiveCases+"（"+pct(s.positiveRecovery.firstAttemptSuccessRate)+"）"],
 ["含恢复后的正例通过",s=>s.positiveRecovery.afterRecoveryPassed+"/"+s.positiveCases+"（"+pct(s.positiveRecovery.afterRecoverySuccessRate)+"）"],
 ["尝试恢复的任务/恢复后通过",s=>s.recovery.recoveryTasks+"/"+s.recovery.recoveredTasksPassed],
 ["恢复增加的物理请求/捕获请求",s=>s.recovery.extraModelAttempts+"/"+s.recovery.extraCapturedRequests],
 ["恢复请求累计耗时",s=>seconds(s.recovery.extraDurationMs)],
 ["恢复前失败请求累计等待",s=>seconds(s.recovery.failedInitialDurationMs)],
 ["耗时P50（全部尝试）",s=>seconds(s.latencyAllAttempts.p50Ms)],
 ["耗时P95（全部尝试）",s=>seconds(s.latencyAllAttempts.p95Ms)],
 ["尝试的模型轮数",s=>String(s.attemptedModelRounds)],
 ["已捕获模型请求数",s=>String(s.modelCalls)],
 ["平均模型调用次数",s=>s.meanModelCalls?.toFixed(2)||"—"],
 ["模型工具调用总数",s=>String(s.toolCalls)],
 ["地图HTTP请求总数",s=>String(s.mapHttpRequests)],
 ["模型请求累计节流等待",s=>seconds(s.totalQueueWaitMs)],
 ["转发请求P50（扣除节流等待）",s=>seconds(s.forwardedLatencyExcludingQueue.p50Ms)],
 ["模型输出截断次数",s=>String(s.truncatedModelResponses)],
 ["输入Token（已知部分）",s=>String(s.knownInputTokens??"未知")],
 ["输出Token（已知部分）",s=>String(s.knownOutputTokens??"未知")],
 ["用量完整的调用数",s=>s.usageKnownCalls+"/"+s.totalUsageCalls],
 ["地点可落到固定数据",s=>pct(s.groundingRate)],
 ["正确路线元数据覆盖率",s=>pct(s.reportedRouteCoverage)],
];
let markdown="# 旅游规划 Agent 适配评测报告\n\n";
markdown+="运行："+report.metadata.runId+"；阶段："+report.metadata.phase+"；模式："+report.metadata.mode+"；模型："+report.metadata.model+"。\n\n";
if(report.metadata.mode==="fixture")markdown+="本报告是脚本联调结果，模型输出由固定替身生成，不作为模型效果证据。\n\n";
markdown+="## 评测口径\n\n";
if(report.metadata.baselineRun)markdown+="本轮对照基线为"+report.metadata.baselineRun+"，使用同一40条用例作缺陷修复后的回归复测。原holdout分组已经用于定位缺陷，此处保留名称便于对照，不能视为未见数据的泛化验证。修复前后比较见同目录comparison.md。\n\n";
markdown+="- 基于ChinaTravel改编的单人目的地内任务，非ChinaTravel官方成绩；原题ID和改动保存在数据集。\n";
markdown+="- 两组使用相同模型、temperature、思考配置、输出上限和固定参考事实；Agent运行本次归档代码的生产提示词、工具及后端规范化，一次生成组无工具、无修复。比较的是应用流程，不是两个不同模型。\n";
if(report.metadata.deterministicPolicy)markdown+="- Agent沿用项目既有住宿/餐饮/其他费用预留基准，明显低于必要预留的预算由Java直接拒绝，模型调用记0；审核必须验证请求天数、预算、各项预留和拒绝证据，不能用通用报错冒充零调用成功。两组任务共享说明中的费用基准保持一致。\n";
if(report.metadata.visitRequirementPolicy)markdown+="- 本轮把两组原已共享参考表的景点游览下限，映射为Agent API的结构化visitRequirements，由服务端保存并执行。该输入适配属于本次改造；审核核对调用方传值及服务端回显，来源仅为共享公开参考字段，不读取expect标签或评分结果。高德接口不被描述为提供这些游览下限。\n";
if(report.metadata.ticketRequirementPolicy)markdown+="- 相对v9，本轮增加同一共享参考表ticketBudget到Agent API门票预留约束的结构化绑定，服务端按来源校验；不是实时高德售价，也不读取隐藏评分标签。\n";
if(report.metadata.modelRecoveryPolicy)markdown+="- 模型每次物理请求最多75秒，每个任务最多一次显式传输恢复，使用新连接，仍受8轮和任务总时限限制。仅在未取得可用响应时恢复，不重放已完成工具。首次通过指任务未使用传输恢复且独立规则通过，包含正常规划及草稿修正轮次；含恢复通过为最终结果。额外耗时是恢复请求实际耗时，恢复前失败等待另列，全部计入端到端。转发器对两组均关闭空闲连接自动断开；与v9存在明确的传输策略变化。\n";
markdown+="- 地点来自固定的ChinaTravel景点子集；路段距离/时间/费用由明确的离线公式模拟，不评估实时高德可用性、真实票价、营业时间或日期安排。\n";
markdown+="- 每条每组一次正式尝试，不因评分失败重试；单次调用的失败及用量缺失均保留。同时运行的任务对上限为"+report.metadata.concurrency+"。\n";
if(report.metadata.pacing)markdown+="- 全局节流：新请求间隔至少"+report.metadata.pacing.minIntervalMs+"ms；滚动60秒预留Token预算"+report.metadata.pacing.tokenBudget+"，请求返回后按实际usage回补。429后后续请求等待65秒；失败的当次请求不重试。队列等待计入端到端耗时。\n";
markdown+="- 主指标为应完成任务的独立规则全部通过率；不可行预算和无公交方案另统计正确拒绝，通用报错不算正确拒绝。评分独立于应用AgentRules。\n";
markdown+="- 这是小样本单次运行，无统计显著性或真实用户收益结论。Token仅列供应商返回的已知用量，未绑定价目表，不估算金额。\n\n";
markdown+="- 正确拒绝只检查拒绝决定及原因类别，不认证解释中每个数字；末轮关键词命中仅供诊断，不等同于模型正确拒绝。Token统计仅对应本报告中的唯一尝试，不代表全部预检或账户账单。\n\n";
for(const split of ["all","development","holdout"]){
  markdown+="## "+{all:"全部用例",development:"调试集",holdout:"留出集"}[split]+"\n\n";
  markdown+="| 指标 | 一次生成 | 工具调用 Agent |\n| --- | --- | --- |\n";
  for(const [name,format]of metrics)markdown+="| "+name+" | "+format(groups.one_shot[split])+" | "+format(groups.agent[split])+" |\n";
  markdown+="\n";
}
markdown+="## 按任务类型\n\n| 类型 | 一次生成 | Agent |\n| --- | --- | --- |\n";
for(const [name,values]of Object.entries(scenarioGroups)){
  const format=s=>name==="refusal"?s.correctRefusals+"/"+s.negativeCases:s.positivePassed+"/"+s.positiveCases;
  markdown+="| "+{plan:"新建行程",revise:"局部修改",refusal:"明确不可行"}[name]+" | "+format(values.one_shot)+" | "+format(values.agent)+" |\n";
}
markdown+="\n";
markdown+="## 结果观察\n\n";
for(const group of ["one_shot","agent"]){
  const s=groups[group].all;
  markdown+="- "+headings[group]+"："+s.completedPositive+"/"+s.positiveCases+"个正例返回行程JSON，其中"+s.positivePassed+"个全部规则通过；负例正确拒绝"+s.correctRefusals+"/"+s.negativeCases+"。\n";
}
const unsafeNegative=rows.filter(r=>r.expectedOutcome==="refused"&&!r.score.correctRefusal&&r.observation.plan?.success===true);
if(unsafeNegative.length)markdown+="- 不可行任务仍交付行程："+unsafeNegative.map(r=>r.caseId+"/"+headings[r.group]).join("、")+"。这是本轮的交付约束缺口，应核对路线可用性等前置条件。\n";
markdown+="- 主分数之外，应同时检查未交付草稿、参考事实漏检与调用开销。当前样本不支持把增加工具轮数本身当作效果提升。\n\n";
markdown+="## 失败归因\n\n| 检查项 | 一次生成失败数 | Agent失败数 |\n| --- | ---: | ---: |\n";
const reasons=new Set([...Object.keys(groups.one_shot.all.failureCounts),...Object.keys(groups.agent.all.failureCounts)]);
for(const reason of reasons)markdown+="| "+(checkLabels[reason]||reason)+"（"+reason+"） | "+(groups.one_shot.all.failureCounts[reason]||0)+" | "+(groups.agent.all.failureCounts[reason]||0)+" |\n";
markdown+="\n检查项可重叠；没有交付行程的正例会同时不满足多个检查，不能视为多个独立事实错误。summary.json另列仅已交付行程的失败归因与接口错误码统计。\n";
markdown+="\n## 每条结果\n\n| ID | 集合 | 组 | 结果 | 耗时 | 失败检查项 |\n| --- | --- | --- | --- | --- | --- |\n";
for(const row of [...rows].sort((a,b)=>(a.caseId+a.group).localeCompare(b.caseId+b.group)))
  markdown+="| "+[row.caseId,row.split,headings[row.group],row.score.taskPassed?"通过":row.score.infrastructureError?"基础设施错误":"未通过",seconds(row.durationMs),failureSummary(row)].join(" | ")+" |\n";
markdown+="\n## 复现信息\n\n";
markdown+="- 数据SHA256："+report.metadata.datasetSha256+"\n- 固定环境SHA256："+report.metadata.frozenSha256+"\n- 协议/代码指纹："+report.metadata.protocolFingerprint+"\n";
markdown+="- 原始逐条模型输入、输出、工具轨迹及独立评分见同目录results.json；模型密钥与账号不进入报告。\n";
markdown+="- 模型轮数包含连接失败的尝试；已捕获请求数只计到达本地转发器的请求，两者可能不同。未到达转发器的连接失败不虚构响应、用量或工具轨迹。\n";
const reused=rows.filter(r=>r.reusedFromRun).length;
if(reused)markdown+="- 本轮复用此前经核验的"+reused+"条尝试，保留原运行ID和数据集哈希，不重复计数。\n";
if(report.metadata.reuseCompatibility)markdown+="- 兼容迁移只纠正修改任务的一次生成输出格式：全部修改场景重新运行；其他场景核对完整一次生成请求、任务/事实、Java构建、评分器、节流配置与执行函数。复用不依据成功与否。\n- 本报告含两个采集时段的结果；按场景配对比较。耗时包含节流且采集时间不同，不解释为连续压测或纯模型速度。\n";
await writeFile(resolve(dirname(path),"report.md"),markdown);
console.log(JSON.stringify({runId:report.metadata.runId,mode:report.metadata.mode,complete:digest.experimentCompleted,
  oneShotPassed:groups.one_shot.all.positivePassed,agentPassed:groups.agent.all.positivePassed,
  positiveCases:groups.agent.all.positiveCases,negativeCases:groups.agent.all.negativeCases}));

