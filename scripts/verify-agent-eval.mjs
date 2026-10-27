// Audit saved experiments without sending any model or map requests.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { hash, sharedContext, fixturePlan, wirePayload, baselinePayload } from "./agent-eval/protocol.mjs";
import { scoreCase } from "../eval/agent-v1/score.mjs";
import {verifyRevisionOnlyMigration,reusablePair} from "./agent-eval/reuse.mjs";
import {verifyBudgetProof} from "./agent-eval/budget-proof.mjs";
import {verifyVisitRequirements} from "./agent-eval/visit-requirements.mjs";
import {verifyTicketRequirements} from "./agent-eval/ticket-requirements.mjs";
import {verifyRecovery} from "./agent-eval/model-recovery.mjs";

const root=fileURLToPath(new URL("../",import.meta.url));
const input=process.argv.find(x=>x.startsWith("--input="))?.slice(8);
assert(input,"--input=path/to/results.json is required");
const experiment=JSON.parse(await readFile(resolve(root,input),"utf8"));
const {metadata,results}=experiment;
const archiveArg=process.argv.find(x=>x.startsWith("--archive="))?.slice(10);
const sourceRoot=archiveArg?resolve(root,archiveArg,"source"):root;
if(archiveArg){
  const manifest=JSON.parse(await readFile(resolve(root,archiveArg,"manifest.json"),"utf8"));
  assert.equal(manifest.run,metadata.runId);assert.deepEqual(manifest.sourceFileSha256,metadata.sourceFileSha256);
  for(const [file,digest]of Object.entries(manifest.assetSha256))
    assert.equal(hash(await readFile(resolve(root,file))),digest,"Archived baseline asset changed: "+file);
}
assert.equal(metadata.mode,"live","Only a real-model experiment is formal evidence");
assert(["1.1-explicit-units-and-budget","1.2-paced-upstream","1.3-revision-patch-output"].includes(metadata.protocolVersion));
const datasetText=await readFile(resolve(root,"eval/agent-v1/"+(metadata.phase==="full"?"dataset":"pilot")+".json"),"utf8");
const dataset=JSON.parse(datasetText);
const frozenText=await readFile(resolve(root,"eval/agent-v1/frozen-sandbox.json"),"utf8");
const frozen=JSON.parse(frozenText);
assert.equal(hash(datasetText),metadata.datasetSha256);
assert.equal(hash(frozenText),metadata.frozenSha256);
assert.equal(dataset.cases.length,metadata.phase==="full"?40:10);
assert.equal(results.length,dataset.cases.length*2);
assert.equal(new Set(results.map(r=>r.caseId+":"+r.group)).size,results.length);
assert.equal(new Set(dataset.cases.map(c=>c.id)).size,dataset.cases.length);
const publicCases=dataset.cases.filter(c=>c.provenance.source==="ChinaTravel/easy");
assert.equal(publicCases.length,metadata.phase==="full"?30:10);
assert.equal(new Set(publicCases.map(c=>c.provenance.sourceUid)).size,publicCases.length);
for(const [file,digest]of Object.entries(dataset.sourceFileSha256))
  assert.equal(hash(await readFile(resolve(root,"eval/agent-v1/source",file))),digest,"Source snapshot differs: "+file);
for(const [file,digest]of Object.entries(metadata.sourceFileSha256))
  assert.equal(hash(await readFile(resolve(sourceRoot,file))),digest,"Frozen code differs: "+file);
if(metadata.reuseCompatibility){
  const source=metadata.reuseCompatibility.sourceRun;
  assert(/^[a-zA-Z0-9_-]+$/.test(source));
  const sourceDir=resolve(root,"eval/agent-v1/results",source);
  const prior=JSON.parse(await readFile(resolve(sourceDir,"results.json"),"utf8"));
  verifyRevisionOnlyMigration(prior.metadata,metadata,
    await readFile(resolve(sourceDir,"protocol-source.mjs"),"utf8"),
    await readFile(resolve(sourceRoot,"scripts/agent-eval/protocol.mjs"),"utf8"),
    await readFile(resolve(sourceDir,"runner-source.mjs"),"utf8"),
    await readFile(resolve(sourceRoot,"scripts/run-agent-eval.mjs"),"utf8"));
  const reusedCases=[];
  for(const c of dataset.cases){
    const reused=results.filter(r=>r.caseId===c.id&&r.reusedFromRun);
    if(!reused.length)continue;
    assert.notEqual(c.kind,"revise","Revisions must all be rerun after output-contract correction");
    const expected=wirePayload(baselinePayload(c,metadata.model,
      Math.min(metadata.maxOutputTokens,Math.max(4096,1200+c.input.days*700)),metadata.enableThinking),sharedContext(c,frozen));
    const pair=reusablePair(prior.results,c,hash(sharedContext(c,frozen)),expected);
    assert.equal(pair.length,2);assert.equal(reused.length,2);reusedCases.push(c.id);
    for(const row of reused){
      const original=pair.find(r=>r.group===row.group);
      assert.equal(row.reusedFromRun,source);
      assert.deepEqual(row.observation,original.observation);
      assert.deepEqual(row.modelRecords,original.modelRecords);
      assert.equal(row.reuseProof.baselineRequestSha256,hash(expected));
    }
  }
  assert.deepEqual(reusedCases,metadata.reuseCompatibility.reusedCases);
}
let witnesses=0;
for(const c of dataset.cases){
  if(c.expectedOutcome==="completed"){
    const witness=fixturePlan(c,frozen);
    const score=scoreCase(c,{plan:witness,patchScopeValid:true},frozen);
    assert.equal(score.taskPassed,true,"No feasible witness: "+c.id+" "+score.failures);
    witnesses++;
  }
  for(const group of ["one_shot","agent"]){
    const row=results.find(r=>r.caseId===c.id&&r.group===group);
    assert(row,"Missing case/group: "+c.id+":"+group);
    assert.equal(row.caseSha256,hash(c));
    assert.equal(row.contextSha256,hash(sharedContext(c,frozen)));
    assert.deepEqual(row.score,scoreCase(c,row.observation,frozen),"Saved scoring differs: "+c.id+":"+group);
    assert.equal(row.modelCalls,row.modelRecords.length);
    if(metadata.visitRequirementPolicy&&group==="agent")verifyVisitRequirements(c,row,frozen);
    if(metadata.ticketRequirementPolicy&&group==="agent")verifyTicketRequirements(c,row,frozen);
    if(metadata.modelRecoveryPolicy)verifyRecovery(row);
    const startedWithoutRelayRecord=row.modelCalls===0&&row.score.infrastructureError
      &&(group==="one_shot"||row.observation.job?.modelRounds>=1);
    const deterministicRefusal=group==="agent"&&row.modelCalls===0
      &&row.observation.job?.refusal?.code==="BUDGET_BELOW_MINIMUM"&&verifyBudgetProof(c,row);
    assert(row.modelCalls>=1||startedWithoutRelayRecord||deterministicRefusal,"No model attempt, infrastructure failure or checked budget proof recorded");
    assert(Number.isFinite(row.durationMs)&&row.durationMs>=0);
    for(const record of row.modelRecords){
      assert.equal(record.contextSha256,row.contextSha256);
      assert.equal(record.request.model,metadata.model);
      assert.equal(record.request.temperature,metadata.temperature);
      assert.equal(record.request.messages[0].role,"system");
      assert.equal(record.request.messages.filter(m=>m.role==="system").length,1);
      assert(record.request.messages[0].content.includes(sharedContext(c,frozen)));
      assert(!Object.keys(record).some(k=>/authorization|api.?key/i.test(k)));
    }
    if(group==="one_shot"){
      assert(row.modelCalls<=1,"One-shot baseline made multiple calls");
      assert.equal(row.toolCalls,0);
      if(row.modelRecords.length)assert(!row.modelRecords[0].request.tools?.length);
    }else{
      assert(row.modelCalls<=8&&row.toolCalls<=16);
      if(row.modelRecords.length)assert(row.modelRecords[0].request.tools?.length>0);
    }
  }
}
if(metadata.phase==="full"){
  assert.equal(dataset.cases.filter(c=>c.split==="development").length,30);
  assert.equal(dataset.cases.filter(c=>c.split==="holdout").length,10);
  const pilot=JSON.parse(await readFile(resolve(root,"eval/agent-v1/pilot.json"),"utf8"));
  for(const c of pilot.cases){
    assert.equal(c.split,"development");
    assert.deepEqual(c,dataset.cases.find(x=>x.id===c.id),"Expanded dataset changed a pilot case");
  }
  assert.equal(dataset.cases.filter(c=>c.kind==="revise").length,8);
  assert.equal(dataset.cases.filter(c=>c.expectedOutcome==="refused").length,2);
}
console.log(JSON.stringify({runId:metadata.runId,auditedCaseGroups:results.length,feasiblePositiveWitnesses:witnesses,
  sourceHashesVerified:true,protocolHashesVerified:true,rescoringIdentical:true,
  equalSharedEvidence:true,reusedCaseGroups:results.filter(r=>r.reusedFromRun).length}));
