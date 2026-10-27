// Read-only analysis of a completed run. Never sends model/map requests or changes its evidence.
import assert from "node:assert/strict";
import {readFile, writeFile, mkdir} from "node:fs/promises";
import {resolve} from "node:path";
import {fileURLToPath} from "node:url";
import {createHash} from "node:crypto";

const root=fileURLToPath(new URL("../",import.meta.url));
const run=process.argv.find(x=>x.startsWith("--run="))?.slice(6)||"full-live-v5-routes";
assert(/^[a-zA-Z0-9_-]+$/.test(run));
const path=resolve(root,"eval/agent-v1/results",run,"results.json");
const bytes=await readFile(path), experiment=JSON.parse(bytes);
const frozen=JSON.parse(await readFile(resolve(root,"eval/agent-v1/frozen-sandbox.json"),"utf8"));
assert.equal(experiment.metadata.experimentCompleted,true);
const parse=text=>{try{return JSON.parse(text);}catch{return null;}};
const rows=experiment.results.filter(r=>r.group==="agent"&&r.kind==="plan"&&!r.score.taskPassed).map(r=>{
  const feedback=new Map();
  for(const m of r.modelRecords)for(const t of m.request.messages.filter(v=>v.role==="tool"))feedback.set(t.tool_call_id,t.content);
  const rounds=r.modelRecords.map((m,i)=>({round:i+1,httpStatus:m.httpStatus,
    queueWaitMs:m.queueWaitMs||0,durationMs:m.durationMs,
    calls:(m.response?.choices?.[0]?.message?.tool_calls||[]).map(t=>{
      const args=parse(t.function.arguments), draft=args?.draftJson;
      const result=parse(feedback.get(t.id));
      return {tool:t.function.name,argumentsBytes:Buffer.byteLength(t.function.arguments),
        ...(t.function.name==="validate_itinerary"?{nestedJsonString:typeof draft==="string",
          malformedNestedJson:typeof draft==="string"&&parse(draft)===null}:{}),
        ...(result?.issues?{issues:result.issues}:{}),...(result?.error?{error:result.error}:{}),
        ...(result?.valid!==undefined?{valid:result.valid}:{})};
    })}));
  const issues=[...new Set([...rounds.flatMap(m=>m.calls.flatMap(t=>t.issues||t.error||[])),
    ...(r.observation.job?.validation?.issues||[])])];
  const referenceVisits=[];
  for(const day of r.observation.plan?.dailyItinerary||[])for(const name of ["morning","afternoon","evening"]){
    const slot=day[name];if(slot.rest)continue;
    const p=frozen.cities[r.observation.plan.city].places.find(p=>p.id===slot.placeId);
    if(p&&slot.visitMinutes<p.minVisitMinutes)referenceVisits.push({day:day.day,slot:name,placeId:p.id,placeName:p.name,
      actualMinutes:slot.visitMinutes,referenceMinimum:p.minVisitMinutes});
  }
  const calls=rounds.flatMap(m=>m.calls),queryCount=calls.filter(t=>["search_places","route_places"].includes(t.tool)).length;
  const submissions=calls.filter(t=>t.tool==="validate_itinerary").length;
  const plainTextReplies=r.modelRecords.filter(m=>!m.response?.choices?.[0]?.message?.tool_calls?.length
      &&m.response?.choices?.[0]?.message?.content?.trim()).length;
  return {caseId:r.caseId,errorCode:r.observation.error?.code||r.observation.job?.refusal?.code,
    status:r.observation.job?.status,durationMs:r.durationMs,modelCalls:r.modelCalls,
    attemptedModelRounds:r.observation.job?.modelRounds??r.modelCalls,queryCount,submissions,plainTextReplies,referenceVisits,
    inputTokens:r.inputTokens,outputTokens:r.outputTokens,
    queueWaitMs:rounds.reduce((n,m)=>n+m.queueWaitMs,0),mapHttpRequests:r.mapCalls.length,
    issues,independentFailures:r.score.failures,
    malformedDrafts:rounds.flatMap(m=>m.calls).filter(t=>t.malformedNestedJson).length,rounds};
});
const count=predicate=>rows.filter(predicate).length;
const summary={run,resultsSha256:createHash("sha256").update(bytes).digest("hex"),failedPlanningCases:rows.length,
  terminalErrors:Object.fromEntries([...new Set(rows.map(r=>r.errorCode||"INDEPENDENT_RULE_FAILURE"))]
    .map(k=>[k,count(r=>(r.errorCode||"INDEPENDENT_RULE_FAILURE")===k)])),
  overlappingEvidence:{mapRequestFailed:count(r=>r.issues.some(s=>s.includes("地图请求未完成"))),
    malformedNestedJson:count(r=>r.malformedDrafts>0),
    restSlotFeedback:count(r=>r.issues.some(s=>/酒店|休息/.test(s))),
    modelTimeout:count(r=>r.errorCode==="MODEL_TIMEOUT"),taskTimeout:count(r=>r.errorCode==="AGENT_TIMEOUT")},cases:rows};
const out=resolve(root,"eval/agent-v1/analysis",run);await mkdir(out,{recursive:true});
await writeFile(resolve(out,"failures.json"),JSON.stringify(summary,null,2)+"\n");
let md="# 新建行程失败记录分析\n\n";
md+=`来源：${run}；results.json SHA-256：${summary.resultsSha256}。只读提取已保存的请求、工具反馈及最终状态，没有重新请求模型。\n\n`;
md+="## 终止原因与过程证据\n\n";
for(const [code,n]of Object.entries(summary.terminalErrors))md+=`- ${code}：${n} 条。\n`;
md+="\n过程证据可以重叠；终止错误码不等同于最早的触发原因。\n\n";
for(const [key,n]of Object.entries(summary.overlappingEvidence))md+=`- ${key}：${n} 条。\n`;
md+="\n| 用例 | 终止错误 | 捕获/尝试模型轮数 | 耗时/秒 | 显式查询/提交草稿 | 工具反馈 |\n| --- | --- | ---: | ---: | ---: | --- |\n";
for(const r of rows)md+=`| ${r.caseId} | ${r.errorCode||"独立规则未通过"} | ${r.modelCalls}/${r.attemptedModelRounds} | ${(r.durationMs/1000).toFixed(2)} | ${r.queryCount}/${r.submissions} | ${r.issues.join("；")||r.referenceVisits.map(v=>v.placeName+"："+v.actualMinutes+"分钟<参考"+v.referenceMinimum+"分钟").join("；")||r.independentFailures.join("；")} |\n`;
md+="\n## 修复方向\n\n";
md+="1. 显式查询设置上限，并保留最后两轮用于草稿提交/修复；预算工具必须实际成功调用。模型仅返回文字说明时，进行有上限的工具补正，计入同一8轮预算。网络失败不自动重试。\n";
md+="2. 名称优先使用实际查询ID及精确名称，再做空白、全角ASCII字符的唯一格式匹配；保留分店、标点和词语信息。规范化后多ID冲突时要求明确ID，不根据简称猜测。\n";
md+="3. 游览时间区分通用15～360范围与调用方提供的具体景点下限；将已经共享的参考表映射为结构化请求约束，服务端持有、模型不能覆盖。校验报告具体字段、实际值、要求值，并按下限重算每日时间可行性。高德不被描述为提供这些下限。\n";
md+="4. 新建与局部修改都支持这些约束；未提供明确下限时保留通用检查及说明，局部修改保留未选历史活动。基础设施失败继续计入分母，捕获请求数不代表全部已尝试轮数。\n";
md+="5. 正式复测保持模型、数据、独立评分器、任务协议、轮数上限及节流配置一致；80 个尝试全部重新运行，基础设施失败照常计入。\n\n";
md+="本轮使用过的 40 条属于回归集，不是新盲测。真实高德服务准确性不在离线合成路线评测范围内。\n";
await writeFile(resolve(out,"analysis.md"),md);
console.log(JSON.stringify({output:"eval/agent-v1/analysis/"+run,failedCases:rows.length,
  terminalErrors:summary.terminalErrors,evidence:summary.overlappingEvidence}));
