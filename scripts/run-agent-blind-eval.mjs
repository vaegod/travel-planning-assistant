// Explicit live mode uses the configured model. All maps replay frozen data on loopback.
import assert from "node:assert/strict";
import { readFile, writeFile, mkdir, readdir } from "node:fs/promises";
import { resolve, relative, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, execFileSync } from "node:child_process";
import { createServer } from "node:net";
import { localClient, testAccount } from "./local-api.mjs";
import { startFrozenProvider } from "./agent-eval/frozen-provider.mjs";
import { baselinePayload, sharedContext, hash, marker, clone, wirePayload } from "./agent-eval/protocol.mjs";
import { deliverBaseline, scoreCase } from "../eval/agent-v1/score.mjs";
import {verifyRevisionOnlyMigration,reusablePair} from "./agent-eval/reuse.mjs";
import {referenceVisitRequirements} from "./agent-eval/visit-requirements.mjs";
import {referenceTicketRequirements} from "./agent-eval/ticket-requirements.mjs";
import {recoveryEvidence} from "./agent-eval/model-recovery.mjs";
import {BLIND_FOLDER,V10_RUN,verifyBlindSet,claimBlindRun,requireUnexposedBlindSet,verifyBaselineConfiguration} from "./agent-eval/blind-contract.mjs";

const root=fileURLToPath(new URL("../",import.meta.url));
const arg=(name,fallback)=>process.argv.find(x=>x.startsWith("--"+name+"="))?.slice(name.length+3) ?? fallback;
const phase=arg("phase","blind"), mode=process.argv.includes("--live")?"live":"fixture";
assert.equal(mode,"live","Blind cases cannot be used for fixture/pilot debugging");
const concurrency=Number(arg("concurrency",mode==="live"?"1":"2"));
assert([1,2].includes(concurrency),"Concurrency must be 1 or 2 task pairs");
const pacing=mode==="live"?{tokenBudget:Number(arg("token-budget-per-minute","40000")),
  windowMs:60000,minIntervalMs:Number(arg("min-model-interval-ms","5000"))}:null;
assert.equal(phase,"blind");
const sealed=await verifyBlindSet(root);
await requireUnexposedBlindSet(root);
const datasetPath=resolve(root,BLIND_FOLDER,"dataset.json");
const datasetText=await readFile(datasetPath,"utf8"),dataset=JSON.parse(datasetText);
const frozenText=await readFile(resolve(root,BLIND_FOLDER,"frozen-sandbox.json"),"utf8"),frozen=JSON.parse(frozenText);
const cases=dataset.cases;
const stamp=new Date().toISOString().replace(/[-:]/g,"").replace(/[.].*/,"");
const runId=arg("run-id",phase+"-"+mode+"-"+stamp);
assert(/^[a-zA-Z0-9_-]+$/.test(runId));
const outDir=resolve(root,"eval/agent-v1/results",runId);
const runtime=resolve(root,".runtime/agent-eval",runId);
await mkdir(outDir,{recursive:true});await mkdir(runtime,{recursive:true});
const localSettings=Object.fromEntries((await readFile(resolve(root,"travel-java-server/.env"),"utf8")).split(/\r?\n/)
  .filter(x=>/^[A-Z_]+=/.test(x)).map(x=>[x.slice(0,x.indexOf("=")),x.slice(x.indexOf("=")+1)]));
const settings={...localSettings,...process.env};
assert(/^jdbc:mysql:\/\/[^/]+\/travel_ai_test(?:[?].*)?$/.test(settings.DB_TEST_URL||""),"Isolated travel_ai_test database required");
if(mode==="live")assert(settings.LLM_API_KEY,"Model key is required");
settings.LLM_BASE_URL ||= "https://api.siliconflow.cn/v1";
const model=mode==="live"?(settings.LLM_MODEL||"Qwen/Qwen3.5-35B-A3B"):"fixture-model";
const thinkingValue=(settings.LLM_ENABLE_THINKING||"").toLowerCase();
const thinking=thinkingValue==="true"?true:thinkingValue==="false"?false:null;
const ceiling=Number(settings.LLM_MAX_OUTPUT_TOKENS||16384);
const hashes={};
async function scan(directory) {
  for(const entry of await readdir(directory,{withFileTypes:true})){
    const path=resolve(directory,entry.name);
    if(entry.isDirectory())await scan(path);
    else if(/\.(java|yml)$/.test(entry.name))hashes[relative(root,path).replaceAll("\\","/")]=hash(await readFile(path));
  }
}
await scan(resolve(root,"travel-java-server/src/main"));
for(const name of ["scripts/build-agent-eval.py","scripts/agent-eval/protocol.mjs","scripts/agent-eval/frozen-provider.mjs",
  "scripts/agent-eval/pacer.mjs","scripts/agent-eval/reuse.mjs","scripts/local-api.mjs","travel-java-server/pom.xml",
  "scripts/agent-eval/budget-proof.mjs",
  "scripts/agent-eval/visit-requirements.mjs",
  "scripts/agent-eval/ticket-requirements.mjs","scripts/agent-eval/model-recovery.mjs",
  "scripts/run-agent-eval.mjs","eval/agent-v1/score.mjs"])hashes[name]=hash(await readFile(resolve(root,name)));
for(const name of ["scripts/run-agent-blind-eval.mjs","scripts/build-agent-blind.py","scripts/agent-eval/blind-contract.mjs",
  "scripts/report-agent-blind-eval.mjs","scripts/verify-agent-blind-eval.mjs","scripts/archive-agent-blind-eval.mjs",
  BLIND_FOLDER+"/plan.json",BLIND_FOLDER+"/seal.json"])hashes[name]=hash(await readFile(resolve(root,name)));
const protocolFingerprint=hash({hashes,frozenSha256:hash(frozenText),model,thinking,ceiling,mode,temperature:0.2,concurrency,pacing});
const metadata={
  runId,phase,mode,protocolVersion:"1.3-revision-patch-output",startedAt:new Date().toISOString(),datasetVersion:dataset.version,
  datasetPath:BLIND_FOLDER+"/dataset.json",frozenPath:BLIND_FOLDER+"/frozen-sandbox.json",
  blindSealSha256:hash(await readFile(resolve(root,BLIND_FOLDER,"seal.json"))),
  plannedCases:20,expectedCaseGroups:40,blindPolicy:sealed.plan.revealPolicy,
  datasetSha256:hash(datasetText),frozenSha256:hash(frozenText),protocolFingerprint,sourceFileSha256:hashes,
  jarSha256:hash(await readFile(resolve(root,"travel-java-server/target/travel-java-server-0.0.1-SNAPSHOT.jar"))),
  queryRevision:dataset.queryRevision,sandboxRevision:dataset.sandboxRevision,model,temperature:0.2,
  enableThinking:thinking,maxOutputTokens:ceiling,realModelCalls:mode==="live",
  evidencePolicy:"Both groups receive identical frozen reference facts; agent runs the measured production prompt and tools, augmented by the shared offline contract",
  deterministicPolicy:"Agent may refuse before the model when the existing project fixed-expense minimum exceeds the request budget; zero calls require an independently verified arithmetic witness",
  visitRequirementPolicy:"Agent API receives per-place minimum visit durations mapped from the exact frozen public reference facts already shared with both models. No hidden expect labels or scores are used. This measures the structured-constraint workflow, including caller-to-server fact binding.",
  ticketRequirementPolicy:"Agent API receives ticket budget floors with attribution from the exact public frozen facts already shared with both models. This adds structured price-reference binding to v9; it is not real Amap pricing or a pure-model comparison.",
  modelRecoveryPolicy:{maxRecoveryAttemptsPerJob:1,attemptTimeoutMs:75000,minRemainingMs:20000,
    maxPhysicalRounds:8,firstPass:"Task passes all independent rules without transport recovery; normal tool/validation rounds are allowed",
    accounting:"All physical attempts, failed primary waits, recovery calls, tokens and elapsed times retained; no whole-task reruns or tool replays"},
  relayTransportPolicy:{keepAliveTimeoutMs:0,policy:"Disable idle close races on the loopback relay for both groups; client request and job deadlines remain bounded"},
  baselinePolicy:"One model request, raw JSON, no repair and no server factual normalization; revision patch merged without silently accepting out-of-scope keys",
  scoringPolicy:"Independent Node scorer; reference routes and budget floors; no application validator imports; no LLM judge",
  mapPolicy:"ChinaTravel attraction subset + explicitly synthetic fixed route times/fares; zero live Amap calls; not official ChinaTravel scores",
  concurrency,pacing,runAttemptsPerCaseGroup:1,sourceAttribution:"LAMDA-NeSy/ChinaTravel and ChinaTravel-Sandbox, CC-BY-4.0",
};
const report={metadata,results:[]};
const baselineRun=arg("baseline-run",V10_RUN);
assert.equal(baselineRun,V10_RUN,"Blind evaluation is frozen to the v10 application baseline");
if(baselineRun){
  assert(/^[a-zA-Z0-9_-]+$/.test(baselineRun));
  const measured=JSON.parse(await readFile(resolve(root,"eval/agent-v1/results",baselineRun,"results.json"),"utf8"));
  for(const field of ["model","temperature","enableThinking","maxOutputTokens","concurrency","pacing"])
    assert.equal(hash(measured.metadata[field]),hash(metadata[field]),"Baseline comparison changes "+field);
  for(const file of ["eval/agent-v1/score.mjs","scripts/agent-eval/protocol.mjs"])
    assert.equal(measured.metadata.sourceFileSha256[file],metadata.sourceFileSha256[file],"Baseline comparison changes scoring or model task contract");
  metadata.baselineRun=baselineRun;
  metadata.comparisonScope="Disjoint first-use 20-case blind evaluation against the frozen v10 application; old 40-case regression results are descriptive references, not paired optimization gains";
  verifyBaselineConfiguration(metadata,measured);
}
const resultKey=r=>r.caseId+":"+r.group;
const priorFile=arg("reuse",null);
const compatibleFile=arg("reuse-compatible",null);
assert(!priorFile&&!compatibleFile,"Blind evaluation does not reuse previous results");
assert(!(priorFile&&compatibleFile),"Choose strict or narrowly compatible reuse, not both");
if(priorFile){
  const previous=JSON.parse(await readFile(resolve(root,priorFile),"utf8"));
  assert.equal(previous.metadata.protocolFingerprint,protocolFingerprint,"Cannot reuse runs after protocol/model/code changes");
  assert.equal(previous.metadata.jarSha256,metadata.jarSha256,"Cannot reuse a different application build");
  assert(!previous.metadata.invalidReason,"Cannot reuse an invalid experiment");
  assert.equal(new Set(previous.results.map(resultKey)).size,previous.results.length,"Duplicate rows in reused run");
  for(const result of previous.results){
    const c=cases.find(c=>c.id===result.caseId);
    if(!c)continue;
    assert.equal(result.caseSha256,hash(c),"Reused case differs");
    assert.equal(result.contextSha256,hash(sharedContext(c,frozen)),"Reused reference facts differ");
    report.results.push({...result,reusedFromRun:previous.metadata.runId,originalDatasetSha256:previous.metadata.datasetSha256});
  }
}
if(compatibleFile){
  const previousPath=resolve(root,compatibleFile);
  const previous=JSON.parse(await readFile(previousPath,"utf8"));
  const oldProtocol=await readFile(resolve(dirname(previousPath),"protocol-source.mjs"),"utf8");
  const oldRunner=await readFile(resolve(dirname(previousPath),"runner-source.mjs"),"utf8");
  verifyRevisionOnlyMigration(previous.metadata,metadata,oldProtocol,
    await readFile(resolve(root,"scripts/agent-eval/protocol.mjs"),"utf8"),oldRunner,
    await readFile(resolve(root,"scripts/run-agent-eval.mjs"),"utf8"));
  assert.equal(new Set(previous.results.map(resultKey)).size,previous.results.length,"Duplicate source rows");
  const reusedCases=[];
  for(const c of cases){
    const context=sharedContext(c,frozen);
    const expected=wirePayload(baselinePayload(c,model,Math.min(ceiling,Math.max(4096,1200+c.input.days*700)),thinking),context);
    const pair=reusablePair(previous.results,c,hash(context),expected);
    if(pair.length)reusedCases.push(c.id);
    for(const result of pair)report.results.push({...result,reusedFromRun:previous.metadata.runId,
      originalDatasetSha256:previous.metadata.datasetSha256,
      reuseProof:{kind:"revision-output-only",baselineRequestSha256:hash(expected),sourceProtocolFingerprint:previous.metadata.protocolFingerprint}});
  }
  metadata.reuseCompatibility={kind:"revision-output-only",sourceRun:previous.metadata.runId,
    sourceProtocolVersion:previous.metadata.protocolVersion,reusedCases,
    policy:"All revisions rerun; non-revision pairs reused irrespective of score only after exact baseline request, case/context, model, JAR, scorer, pacing and immutable-code checks"};
}
let writes = Promise.resolve();
async function flush(){
  const clean=JSON.stringify(report,null,2);
  assert(!settings.LLM_API_KEY||!clean.includes(settings.LLM_API_KEY),"Secret must not enter evaluation report");
  writes = writes.then(() => writeFile(resolve(outDir,"results.json"),clean+"\n"));
  await writes;
}
try {
  const existing=JSON.parse(await readFile(resolve(outDir,"results.json"),"utf8"));
  assert.fail("Blind run ID already has results; refusing resume or replacement");
  assert.equal(existing.metadata.protocolFingerprint,protocolFingerprint,"Run ID already exists with a different protocol");
  assert.equal(existing.metadata.datasetSha256,metadata.datasetSha256,"Run ID already exists with a different dataset");
  assert.equal(existing.metadata.jarSha256,metadata.jarSha256,"Run ID already exists with a different application build");
  assert(!existing.metadata.invalidReason,"Cannot resume an invalid experiment");
  assert.equal(new Set(existing.results.map(resultKey)).size,existing.results.length,"Duplicate rows in existing run");
  for(const result of existing.results){
    const c=cases.find(c=>c.id===result.caseId);
    assert(c,"Existing row is not in this dataset");
    assert.equal(result.caseSha256,hash(c),"Existing row has different case inputs");
    assert.equal(result.contextSha256,hash(sharedContext(c,frozen)),"Existing row has different reference facts");
  }
  for(const result of existing.results)if(!report.results.some(r=>resultKey(r)===resultKey(result)))report.results.push(result);
}catch(error){if(error.code!=="ENOENT")throw error;}
await flush();
const port=Number(arg("port","3217"));
await new Promise((ok,bad)=>{const probe=createServer();probe.once("error",bad);probe.listen(port,"127.0.0.1",()=>probe.close(ok));});
const relay=await startFrozenProvider({dataset,frozen,mode,settings,pacing});
const java=settings.JAVA_HOME?resolve(settings.JAVA_HOME,"bin",process.platform==="win32"?"java.exe":"java"):"java";
let log="",exited=false;
const app=spawn(java,[...(process.platform==="win32"?["-Djavax.net.ssl.trustStoreType=Windows-ROOT"]:[]),
  "-jar",resolve(root,"travel-java-server/target/travel-java-server-0.0.1-SNAPSHOT.jar")],{
  cwd:resolve(root,"travel-java-server"),windowsHide:true,stdio:["ignore","pipe","pipe"],
  env:{...process.env,DB_URL:settings.DB_TEST_URL,DB_USERNAME:settings.DB_USERNAME,DB_PASSWORD:settings.DB_PASSWORD,
    SPRING_PROFILES_ACTIVE:"agent-eval",SERVER_ADDRESS:"127.0.0.1",SERVER_PORT:String(port),
    LLM_BASE_URL:relay.origin,LLM_API_KEY:"evaluation-relay-only",LLM_MODEL:model,
    LLM_ENABLE_THINKING:thinking===null?"":String(thinking),LLM_MAX_OUTPUT_TOKENS:String(ceiling),
    AMAP_BASE_URL:relay.origin,AMAP_API_KEY:"evaluation-fixture-only",PLANNING_EXTERNAL_ENABLED:"false",
    KNOWLEDGE_ENABLED:"false",FORWARD_HEADERS_STRATEGY:"none",SERVER_SERVLET_SESSION_COOKIE_SECURE:"false",
    AGENT_MAX_ROUNDS:"8",AGENT_MAX_TOOL_CALLS:"16",AGENT_TIMEOUT_MS:"240000"},
});
app.stdout.on("data",part=>{log+=part.toString();});app.stderr.on("data",part=>{log+=part.toString();});
app.once("exit",()=>{exited=true;});app.once("error",()=>{exited=true;});
const origin="http://127.0.0.1:"+port;
let fatalProviderError = null;
async function ready(){
  for(let i=0;i<120;i++){
    if(exited)throw Error("Evaluation backend exited during startup");
    try{if((await fetch(origin+"/api/health",{signal:AbortSignal.timeout(1000)})).ok)return;}catch{}
    await new Promise(r=>setTimeout(r,250));
  }
  throw Error("Evaluation backend not ready");
}
function usage(records){
  const known=records.filter(r=>Number.isFinite(r.response?.usage?.prompt_tokens)&&Number.isFinite(r.response?.usage?.completion_tokens));
  return {modelCalls:records.length,usageKnownCalls:known.length,usageComplete:known.length===records.length,
    inputTokens:known.length?known.reduce((n,r)=>n+r.response.usage.prompt_tokens,0):null,
    outputTokens:known.length?known.reduce((n,r)=>n+r.response.usage.completion_tokens,0):null};
}
async function baseline(c){
  const started=performance.now();let observation={};
  try{
    const payload=baselinePayload(c,model,Math.min(ceiling,Math.max(4096,1200+c.input.days*700)),thinking);
    const response=await fetch(relay.origin+"/chat/completions",{method:"POST",headers:{"Content-Type":"application/json"},
      body:JSON.stringify(payload),signal:AbortSignal.timeout(125000)});
    const value=await response.json();
    if(!response.ok)observation.error={code:"HTTP_"+response.status,message:"Model request failed"};
    else{
      const choice=value.choices?.[0],text=choice?.message?.content||"";
      observation={...deliverBaseline(c,text),rawText:text,visibleText:text,finishReason:choice?.finish_reason};
    }
  }catch(error){observation.error={code:"NETWORK",message:"Baseline model request failed or timed out"};}
  return finishResult(c,"one_shot",observation,performance.now()-started);
}
async function agent(c,client){
  const started=performance.now();let job,observation={};
  const suppliedVisitRequirements=referenceVisitRequirements(c,frozen);
  const suppliedTicketRequirements=referenceTicketRequirements(c,frozen);
  try{
    const request={...c.input,instruction:marker(c.id)+"\n"+c.input.instruction,requestId:crypto.randomUUID(),visitRequirements:suppliedVisitRequirements,ticketRequirements:suppliedTicketRequirements};
    if(c.kind==="revise"){
      const base=await client.api("/api/trips","POST",{title:"Agent evaluation "+c.id,plan:c.basePlan});
      job=await client.api("/api/agent/trips/"+base.trip.id+"/revisions","POST",{
        requestId:request.requestId,baseVersion:1,...c.revision,instruction:marker(c.id)+"\n"+c.revision.instruction,
        visitRequirements:suppliedVisitRequirements,ticketRequirements:suppliedTicketRequirements});
    }else job=await client.api("/api/agent/jobs","POST",request);
    const id=job.id,waitStarted=performance.now();
    while(["QUEUED","RUNNING"].includes(job.status)){
      if(exited)throw Error("BACKEND_EXIT");
      if(performance.now()-waitStarted>255000)throw Error("AGENT_TIMEOUT");
      await new Promise(r=>setTimeout(r,500));
      job=await client.api("/api/agent/jobs/"+id);
    }
    observation={plan:job.plan,visibleText:job.message||job.progress,job,patchScopeValid:true};
    if(job.status==="REFUSED")observation.refusal=job.refusal;
    else if(job.status!=="READY"&&job.status!=="SAVED")observation.error={code:job.errorCode||"AGENT_FAILED",message:job.message};
  }catch(error){
    observation.error={code:exited?"BACKEND_EXIT":"AGENT_RUN_ERROR",message:String(error.message).slice(0,200)};
    if(job?.id)await client.reply("/api/agent/jobs/"+job.id,"DELETE").catch(()=>{});
  }
  const records=relay.records(c.id,"agent");
  observation.suppliedVisitRequirements=suppliedVisitRequirements;
  observation.suppliedTicketRequirements=suppliedTicketRequirements;
  observation.modelFinalText=records.map(r=>r.response?.choices?.[0]?.message?.content||"").filter(Boolean).at(-1)||"";
  return finishResult(c,"agent",observation,performance.now()-started);
}
async function finishResult(c,group,observation,duration){
  const records=relay.records(c.id,group);
  const metered=usage(records);
  if(group==="agent"&&records.length===0&&observation.job?.modelRounds===0
      &&observation.job?.refusal?.code==="BUDGET_BELOW_MINIMUM"){
    metered.inputTokens=0;metered.outputTokens=0;
  }
  if (records.some(r => [401,402,403,404].includes(r.httpStatus) || r.response?.error?.providerCode === 20015))
    fatalProviderError = "Provider authentication, availability or message protocol rejected the experiment; remaining calls stopped";
  const scoring=scoreCase(c,observation,frozen);
  const result={caseId:c.id,caseSha256:hash(c),contextSha256:hash(sharedContext(c,frozen)),group,split:c.split,
    kind:c.kind,sourceUid:c.provenance.sourceUid||null,expectedOutcome:c.expectedOutcome,
    durationMs:Math.round(duration),...metered,toolCalls:group==="agent"?(observation.job?.toolCalls||0):0,
    mapCalls:group==="agent"?relay.mapCalls(c.id):[],observation,score:scoring,modelRecords:records};
  result.recovery=recoveryEvidence(result);
  report.results.push(result);await flush();
  console.log(JSON.stringify({runId,caseId:c.id,group,passed:scoring.taskPassed,outcome:scoring.outcome,
    failures:scoring.failures,modelCalls:result.modelCalls,durationMs:result.durationMs}));
  return result;
}
try{
  await ready();
  const workers=[];
  for(let i=0;i<concurrency;i++){
    const client=localClient(origin),account=testAccount();await client.register(account);
    await writeFile(resolve(runtime,"account-"+i+".json"),JSON.stringify(account)+"\n");workers.push(client);
  }
  await claimBlindRun(root,runId);
  let index=0;
  const unfinished=cases.filter(c=>!["one_shot","agent"].every(g=>report.results.some(r=>r.caseId===c.id&&r.group===g)));
  await Promise.all(workers.map(async client=>{
    while(index<unfinished.length){
      const c=unfinished[index++];
      const needed=[];
      if(!report.results.some(r=>r.caseId===c.id&&r.group==="one_shot"))needed.push(baseline(c));
      if(!report.results.some(r=>r.caseId===c.id&&r.group==="agent"))needed.push(agent(c,client));
      await Promise.all(needed);
      if(fatalProviderError) throw Error(fatalProviderError);
    }
  }));
  metadata.completedAt=new Date().toISOString();
  metadata.completedCaseGroups=report.results.length;
  metadata.experimentCompleted=report.results.length===cases.length*2;
  await flush();
  console.log(JSON.stringify({runId,experimentCompleted:metadata.experimentCompleted,caseGroups:report.results.length,
    fixtureOnly:mode==="fixture",output:relative(root,outDir)}));
}catch(error){
  metadata.runError=String(error.message).replaceAll(settings.LLM_API_KEY||"__unused_secret__", "[REDACTED]");
  await flush();console.error(metadata.runError);process.exitCode=1;
}finally{
  await writeFile(resolve(runtime,"backend.log"),log.replaceAll(settings.LLM_API_KEY||"__unused_secret__","[REDACTED]"));
  app.kill();await relay.close();
}

