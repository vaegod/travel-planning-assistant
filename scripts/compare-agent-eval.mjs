// Compare paired, fixed-case regression runs without editing either run's results.
import assert from "node:assert/strict";
import {readFile,writeFile} from "node:fs/promises";
import {resolve,dirname} from "node:path";
import {createHash} from "node:crypto";
import {summarizeRecovery} from "./agent-eval/model-recovery.mjs";
const arg=name=>process.argv.find(x=>x.startsWith("--"+name+"="))?.slice(name.length+3);
assert(arg("baseline")&&arg("current"),"--baseline=summary.json --current=summary.json required");
const beforePath=resolve(arg("baseline")),afterPath=resolve(arg("current"));
assert.notEqual(beforePath,afterPath,"Comparison must write to a new run, never the baseline");
const before=JSON.parse(await readFile(beforePath,"utf8")),after=JSON.parse(await readFile(afterPath,"utf8"));
const hash=x=>createHash("sha256").update(JSON.stringify(x)).digest("hex");
for(const field of ["datasetSha256","frozenSha256","model","temperature","enableThinking","maxOutputTokens","concurrency","pacing"])
  assert.equal(hash(before.metadata[field]),hash(after.metadata[field]),"Comparison changes "+field);
for(const file of ["eval/agent-v1/score.mjs","scripts/agent-eval/protocol.mjs"])
  assert.equal(before.metadata.sourceFileSha256[file],after.metadata.sourceFileSha256[file],"Comparison changes scorer or shared task: "+file);
assert(before.experimentCompleted&&after.experimentCompleted);
const beforeRows=JSON.parse(await readFile(resolve(dirname(beforePath),"results.json"),"utf8")).results;
const afterRows=JSON.parse(await readFile(resolve(dirname(afterPath),"results.json"),"utf8")).results;
const recovery=(summary,group)=>summarizeRecovery((summary===before?beforeRows:afterRows)
    .filter(r=>r.group===group&&r.expectedOutcome==="completed"));
assert.equal(beforeRows.length,80);assert.equal(afterRows.length,80);
for(const row of beforeRows){
  const next=afterRows.find(r=>r.caseId===row.caseId&&r.group===row.group);assert(next);
  assert.equal(row.caseSha256,next.caseSha256);assert.equal(row.contextSha256,next.contextSha256);
}
const positive=s=>s.positivePassed+"/"+s.positiveCases;
const refused=s=>s.correctRefusals+"/"+s.negativeCases;
const sec=x=>(x/1000).toFixed(2)+"秒";
const metrics=[
 ["新建行程规则全部通过",v=>positive(v.scenarioGroups.plan.agent),v=>positive(v.scenarioGroups.plan.one_shot)],
 ["局部修改规则全部通过",v=>positive(v.scenarioGroups.revise.agent),v=>positive(v.scenarioGroups.revise.one_shot)],
 ["所有正例规则全部通过",v=>positive(v.groups.agent.all),v=>positive(v.groups.one_shot.all)],
 ["不可行任务正确拒绝",v=>refused(v.groups.agent.all),v=>refused(v.groups.one_shot.all)],
 ["新建行程P50（含节流）",v=>sec(v.scenarioGroups.plan.agent.latencyAllAttempts.p50Ms),v=>sec(v.scenarioGroups.plan.one_shot.latencyAllAttempts.p50Ms)],
 ["新建行程P95（含节流）",v=>sec(v.scenarioGroups.plan.agent.latencyAllAttempts.p95Ms),v=>sec(v.scenarioGroups.plan.one_shot.latencyAllAttempts.p95Ms)],
 ["端到端P50（含节流）",v=>sec(v.groups.agent.all.latencyAllAttempts.p50Ms),v=>sec(v.groups.one_shot.all.latencyAllAttempts.p50Ms)],
 ["平均模型请求次数",v=>v.groups.agent.all.meanModelCalls.toFixed(3),v=>v.groups.one_shot.all.meanModelCalls.toFixed(3)],
 ["新建行程平均模型请求次数",v=>v.scenarioGroups.plan.agent.meanModelCalls.toFixed(3),v=>v.scenarioGroups.plan.one_shot.meanModelCalls.toFixed(3)],
 ["模型输入Token（已知）",v=>v.groups.agent.all.knownInputTokens,v=>v.groups.one_shot.all.knownInputTokens],
 ["模型输出Token（已知）",v=>v.groups.agent.all.knownOutputTokens,v=>v.groups.one_shot.all.knownOutputTokens],
 ["用量完整的模型请求",v=>v.groups.agent.all.usageKnownCalls+"/"+v.groups.agent.all.totalUsageCalls,v=>v.groups.one_shot.all.usageKnownCalls+"/"+v.groups.one_shot.all.totalUsageCalls],
 ["地图HTTP请求（回放端收到）",v=>v.groups.agent.all.mapHttpRequests,v=>v.groups.one_shot.all.mapHttpRequests],
 ["基础设施失败数",v=>v.groups.agent.all.infrastructureErrors,v=>v.groups.one_shot.all.infrastructureErrors],
 ["首次通过正例（未用传输恢复）",v=>recovery(v,"agent").firstAttemptPassed+"/38",v=>recovery(v,"one_shot").firstAttemptPassed+"/38"],
 ["含恢复后的正例通过",v=>recovery(v,"agent").afterRecoveryPassed+"/38",v=>recovery(v,"one_shot").afterRecoveryPassed+"/38"],
 ["恢复增加的物理请求/捕获请求",v=>recovery(v,"agent").extraModelAttempts+"/"+recovery(v,"agent").extraCapturedRequests,v=>recovery(v,"one_shot").extraModelAttempts+"/"+recovery(v,"one_shot").extraCapturedRequests],
 ["恢复请求累计耗时",v=>sec(recovery(v,"agent").extraDurationMs),v=>sec(recovery(v,"one_shot").extraDurationMs)],
];
let md="# Agent优化后的同集回归对比\n\n";
md+="基线："+before.metadata.runId+"；复测："+after.metadata.runId+"。数据、独立评分器、共享任务协议、模型参数及节流配置一致，80个配对结果逐一核验。\n\n";
if(after.metadata.visitRequirementPolicy&&!before.metadata.visitRequirementPolicy)
  md+="本轮增加了调用方到服务端的结构化游览下限绑定：取值来自两组原已共享的公开参考表，API保存并执行这些约束。该适配是本次应用流程改造的一部分；审核同时检查发送值与服务端回显，未新增参考事实或读取隐藏评分标签。\n\n";
if(after.metadata.ticketRequirementPolicy&&!before.metadata.ticketRequirementPolicy)
  md+="相对v9，本轮将同一共享参考表的ticketBudget增加为带来源的API门票下限，由Java执行；这是额外的结构化事实绑定，不能作为纯模型能力提升。\n\n";
if(after.metadata.modelRecoveryPolicy)
  md+="传输策略变化：每次Agent模型请求最多75秒、每任务最多一次新连接恢复，仍受原8轮和总时限限制；转发器对两组关闭空闲连接自动断开。首次通过指未使用传输恢复且任务规则通过，正常规划/修正轮次仍允许；恢复请求耗时与之前失败等待分别记录，所有物理尝试均计入成本，无整条任务补跑。\n\n";
md+="| 指标 | Agent基线 | Agent本次复测 | 一次生成原基线 | 一次生成本次重跑 |\n| --- | ---: | ---: | ---: | ---: |\n";
for(const [label,agent,one]of metrics)md+="| "+[label,agent(before),agent(after),one(before),one(after)].join(" | ")+" |\n";
md+="\n这40条已经用于缺陷定位，本次为同集回归复测。原holdout分组名称保留便于对照，不能再次视作未见数据的泛化验证。单次随机模型输出与不同采集时段都会影响结果；不声称统计显著性或线上效果。路线为离线合成，未测试真实高德服务准确率。\n\n";
md+="预算、门票、游览时长等剩余规则失败仍保留在逐条报告中；本轮不修改评分器、不按失败重跑。模型工具调用与后端自动地图核验分别计数，节流等待计入端到端耗时。\n\n";
const pairs=beforeRows.filter(r=>r.group==="agent").map(r=>{
  const next=afterRows.find(n=>n.caseId===r.caseId&&n.group===r.group);
  return {caseId:r.caseId,kind:r.kind,beforePassed:r.score.taskPassed,afterPassed:next.score.taskPassed,
    beforeCode:r.observation.error?.code||r.observation.job?.refusal?.code||null,
    afterCode:next.observation.error?.code||next.observation.job?.refusal?.code||null,
    beforeModelCalls:r.modelCalls,afterModelCalls:next.modelCalls,beforeDurationMs:r.durationMs,afterDurationMs:next.durationMs};
});
const recovered=pairs.filter(r=>!r.beforePassed&&r.afterPassed),regressed=pairs.filter(r=>r.beforePassed&&!r.afterPassed);
const reconnects=rows=>rows.filter(r=>r.group==="agent").reduce((n,r)=>n+(r.observation.job?.traces||[]).filter(t=>t.tool==="retry_map").length,0);
md+=`Agent由失败变通过：${recovered.map(r=>r.caseId).join("、")||"无"}；由通过变失败：${regressed.map(r=>r.caseId).join("、")||"无"}。\n\n`;
md+=`地图连接重连记录：基线${reconnects(beforeRows)}次，本次${reconnects(afterRows)}次。它属于同一任务内、最多一次的只读GET重连，不是模型请求或整条用例重跑；地图HTTP数仅统计回放端实际收到的请求。\n\n`;
md+="基础设施失败数沿用原评分器口径，地图服务错误和任务总超时另见summary.json的workflowFailureCounts。用量缺失保留为未知；不同采集时段的网络与模型服务速度会影响耗时，不能把全部时间差归因于代码。\n\n";
md+="| 用例 | 基线 | 本次 | 模型次数（前→后） | 秒数（前→后，含节流） |\n| --- | --- | --- | ---: | ---: |\n";
for(const r of pairs)md+=`| ${r.caseId} | ${r.beforePassed?"通过":r.beforeCode||"规则失败"} | ${r.afterPassed?"通过":r.afterCode||"规则失败"} | ${r.beforeModelCalls}→${r.afterModelCalls} | ${sec(r.beforeDurationMs)}→${sec(r.afterDurationMs)} |\n`;
const comparison={baseline:before.metadata.runId,current:after.metadata.runId,
  pairedCasesVerified:40,pairedAttemptsVerified:80,unchangedScorer:true,unchangedDataset:true,
  scope:"same-case regression, not unseen holdout generalization",agent:{before:before.groups.agent,after:after.groups.agent},
  oneShot:{before:before.groups.one_shot,after:after.groups.one_shot},pairedAgentCases:pairs,
  recoveredCases:recovered.map(r=>r.caseId),regressedCases:regressed.map(r=>r.caseId),
  mapReconnects:{before:reconnects(beforeRows),after:reconnects(afterRows)},
  inputAdaptation:after.metadata.visitRequirementPolicy||null};
comparison.ticketInputAdaptation=after.metadata.ticketRequirementPolicy||null;
comparison.recovery={before:recovery(before,"agent"),after:recovery(after,"agent")};
await writeFile(resolve(dirname(afterPath),"comparison.md"),md);
await writeFile(resolve(dirname(afterPath),"comparison.json"),JSON.stringify(comparison,null,2)+"\n");
console.log(JSON.stringify({pairedAttempts:80,agentBefore:positive(before.groups.agent.all),agentAfter:positive(after.groups.agent.all),
  refusedBefore:refused(before.groups.agent.all),refusedAfter:refused(after.groups.agent.all)}));
