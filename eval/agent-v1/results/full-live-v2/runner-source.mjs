// Explicit live mode uses the configured model. All maps replay frozen data on loopback.
import assert from "node:assert/strict";
import { readFile, writeFile, mkdir, readdir } from "node:fs/promises";
import { resolve, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, execFileSync } from "node:child_process";
import { createServer } from "node:net";
import { localClient, testAccount } from "./local-api.mjs";
import { startFrozenProvider } from "./agent-eval/frozen-provider.mjs";
import { baselinePayload, sharedContext, hash, marker, clone } from "./agent-eval/protocol.mjs";
import { deliverBaseline, scoreCase } from "../eval/agent-v1/score.mjs";

const root=fileURLToPath(new URL("../",import.meta.url));
const arg=(name,fallback)=>process.argv.find(x=>x.startsWith("--"+name+"="))?.slice(name.length+3) ?? fallback;
const phase=arg("phase","pilot"), mode=process.argv.includes("--live")?"live":"fixture";
const concurrency=Number(arg("concurrency",mode==="live"?"1":"2"));
assert([1,2].includes(concurrency),"Concurrency must be 1 or 2 task pairs");
const pacing=mode==="live"?{tokenBudget:Number(arg("token-budget-per-minute","40000")),
  windowMs:60000,minIntervalMs:Number(arg("min-model-interval-ms","5000"))}:null;
assert(["pilot","full"].includes(phase));
const datasetPath=resolve(root,"eval/agent-v1/"+(phase==="pilot"?"pilot.json":"dataset.json"));
const datasetText=await readFile(datasetPath,"utf8"),dataset=JSON.parse(datasetText);
const frozenText=await readFile(resolve(root,"eval/agent-v1/frozen-sandbox.json"),"utf8"),frozen=JSON.parse(frozenText);
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
  "scripts/agent-eval/pacer.mjs","scripts/local-api.mjs","travel-java-server/pom.xml",
  "scripts/run-agent-eval.mjs","eval/agent-v1/score.mjs"])hashes[name]=hash(await readFile(resolve(root,name)));
const protocolFingerprint=hash({hashes,frozenSha256:hash(frozenText),model,thinking,ceiling,mode,temperature:0.2,concurrency,pacing});
const metadata={
  runId,phase,mode,protocolVersion:"1.2-paced-upstream",startedAt:new Date().toISOString(),datasetVersion:dataset.version,
  datasetSha256:hash(datasetText),frozenSha256:hash(frozenText),protocolFingerprint,sourceFileSha256:hashes,
  jarSha256:hash(await readFile(resolve(root,"travel-java-server/target/travel-java-server-0.0.1-SNAPSHOT.jar"))),
  queryRevision:dataset.queryRevision,sandboxRevision:dataset.sandboxRevision,model,temperature:0.2,
  enableThinking:thinking,maxOutputTokens:ceiling,realModelCalls:mode==="live",
  evidencePolicy:"Both groups receive identical frozen reference facts; agent uses unchanged production prompt and tools, augmented by shared offline contract",
  baselinePolicy:"One model request, raw JSON, no repair and no server factual normalization; revision patch merged without silently accepting out-of-scope keys",
  scoringPolicy:"Independent Node scorer; reference routes and budget floors; no application validator imports; no LLM judge",
  mapPolicy:"ChinaTravel attraction subset + explicitly synthetic fixed route times/fares; zero live Amap calls; not official ChinaTravel scores",
  concurrency,pacing,runAttemptsPerCaseGroup:1,sourceAttribution:"LAMDA-NeSy/ChinaTravel and ChinaTravel-Sandbox, CC-BY-4.0",
};
const report={metadata,results:[]};
const resultKey=r=>r.caseId+":"+r.group;
const priorFile=arg("reuse",null);
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
let writes = Promise.resolve();
async function flush(){
  const clean=JSON.stringify(report,null,2);
  assert(!settings.LLM_API_KEY||!clean.includes(settings.LLM_API_KEY),"Secret must not enter evaluation report");
  writes = writes.then(() => writeFile(resolve(outDir,"results.json"),clean+"\n"));
  await writes;
}
try {
  const existing=JSON.parse(await readFile(resolve(outDir,"results.json"),"utf8"));
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
  try{
    const request={...c.input,instruction:marker(c.id)+"\n"+c.input.instruction,requestId:crypto.randomUUID()};
    if(c.kind==="revise"){
      const base=await client.api("/api/trips","POST",{title:"Agent evaluation "+c.id,plan:c.basePlan});
      job=await client.api("/api/agent/trips/"+base.trip.id+"/revisions","POST",{
        requestId:request.requestId,baseVersion:1,...c.revision,instruction:marker(c.id)+"\n"+c.revision.instruction});
    }else job=await client.api("/api/agent/jobs","POST",request);
    const id=job.id,waitStarted=performance.now();
    while(["QUEUED","RUNNING"].includes(job.status)){
      if(exited)throw Error("BACKEND_EXIT");
      if(performance.now()-waitStarted>255000)throw Error("AGENT_TIMEOUT");
      await new Promise(r=>setTimeout(r,500));
      job=await client.api("/api/agent/jobs/"+id);
    }
    observation={plan:job.plan,visibleText:job.message||job.progress,job,patchScopeValid:true};
    if(job.status!=="READY"&&job.status!=="SAVED")observation.error={code:job.errorCode||"AGENT_FAILED",message:job.message};
  }catch(error){
    observation.error={code:exited?"BACKEND_EXIT":"AGENT_RUN_ERROR",message:String(error.message).slice(0,200)};
    if(job?.id)await client.reply("/api/agent/jobs/"+job.id,"DELETE").catch(()=>{});
  }
  const records=relay.records(c.id,"agent");
  observation.modelFinalText=records.map(r=>r.response?.choices?.[0]?.message?.content||"").filter(Boolean).at(-1)||"";
  return finishResult(c,"agent",observation,performance.now()-started);
}
async function finishResult(c,group,observation,duration){
  const records=relay.records(c.id,group);
  if (records.some(r => [401,402,403,404].includes(r.httpStatus) || r.response?.error?.providerCode === 20015))
    fatalProviderError = "Provider authentication, availability or message protocol rejected the experiment; remaining calls stopped";
  const scoring=scoreCase(c,observation,frozen);
  const result={caseId:c.id,caseSha256:hash(c),contextSha256:hash(sharedContext(c,frozen)),group,split:c.split,
    kind:c.kind,sourceUid:c.provenance.sourceUid||null,expectedOutcome:c.expectedOutcome,
    durationMs:Math.round(duration),...usage(records),toolCalls:group==="agent"?(observation.job?.toolCalls||0):0,
    mapCalls:group==="agent"?relay.mapCalls(c.id):[],observation,score:scoring,modelRecords:records};
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

