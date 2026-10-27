// Audit only. No model/map requests, no changes to either the regression baseline or blind evidence.
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {resolve} from "node:path";
import {hash,sharedContext,baselinePayload,wirePayload} from "./agent-eval/protocol.mjs";
import {scoreCase} from "../eval/agent-v1/score.mjs";
import {verifyRecovery} from "./agent-eval/model-recovery.mjs";
import {verifyBudgetProof} from "./agent-eval/budget-proof.mjs";
import {verifyVisitRequirements} from "./agent-eval/visit-requirements.mjs";
import {verifyTicketRequirements} from "./agent-eval/ticket-requirements.mjs";
import {BLIND_FOLDER,V10_RUN,verifyBlindSet,verifyBaselineConfiguration} from "./agent-eval/blind-contract.mjs";
const input=process.argv.find(x=>x.startsWith("--input="))?.slice(8);assert(input,"--input=... required");
const root=process.cwd();
const archive=process.argv.find(x=>x.startsWith("--archive="))?.slice(10);
const sourceRoot=archive?resolve(root,archive,"source"):root;
const sealed=await verifyBlindSet(root,{runtime:!archive,sourceRoot});
const report=JSON.parse(await readFile(resolve(root,input),"utf8"));
const {metadata,results}=report;
assert.equal(metadata.mode,"live");assert.equal(metadata.phase,"blind");assert.equal(metadata.experimentCompleted,true);
assert.equal(metadata.baselineRun,V10_RUN);assert.equal(results.length,40);
assert.equal(new Set(results.map(r=>r.caseId+":"+r.group)).size,40);
assert.equal(metadata.datasetSha256,sealed.seal.datasetSha256);assert.equal(metadata.frozenSha256,sealed.seal.frozenSha256);
assert.equal(metadata.blindSealSha256,hash(await readFile(resolve(root,BLIND_FOLDER,"seal.json"))));
verifyBaselineConfiguration(metadata,sealed.baseline);
const exposure=JSON.parse(await readFile(resolve(root,BLIND_FOLDER,"exposure.json"),"utf8"));
assert.equal(exposure.runId,metadata.runId);assert.equal(exposure.attemptsPerCaseGroup,1);
assert(Date.parse(exposure.exposedAt)>=Date.parse(sealed.seal.sealedAt));
if(archive){
  const manifest=JSON.parse(await readFile(resolve(root,archive,"manifest.json"),"utf8"));
  assert.equal(manifest.run,metadata.runId);assert.deepEqual(manifest.sourceFileSha256,metadata.sourceFileSha256);
  for(const[file,digest]of Object.entries(manifest.assetSha256))assert.equal(hash(await readFile(resolve(root,file))),digest);
}
for(const[file,digest]of Object.entries(metadata.sourceFileSha256))
  assert.equal(hash(await readFile(resolve(sourceRoot,file))),digest,"Measured source changed: "+file);
for(const c of sealed.dataset.cases)for(const group of ["one_shot","agent"]){
  const row=results.find(r=>r.caseId===c.id&&r.group===group);assert(row);
  assert(!row.reusedFromRun);assert.equal(row.caseSha256,hash(c));assert.equal(row.contextSha256,hash(sharedContext(c,sealed.frozen)));
  assert.deepEqual(row.score,scoreCase(c,row.observation,sealed.frozen));assert.equal(row.modelCalls,row.modelRecords.length);
  assert(Number.isFinite(row.durationMs)&&row.durationMs>=0);verifyRecovery(row);
  if(group==="agent"){
    verifyVisitRequirements(c,row,sealed.frozen);verifyTicketRequirements(c,row,sealed.frozen);
    assert(row.modelCalls<=8&&row.toolCalls<=16);
    if(row.modelCalls===0&&!row.score.infrastructureError)assert(verifyBudgetProof(c,row));
  } else {
    assert(row.modelCalls<=1);assert.equal(row.toolCalls,0);
    const expected=wirePayload(baselinePayload(c,metadata.model,
      Math.min(metadata.maxOutputTokens,Math.max(4096,1200+c.input.days*700)),metadata.enableThinking),sharedContext(c,sealed.frozen));
    if(row.modelCalls)assert.deepEqual(row.modelRecords[0].request,expected,"Baseline prompt/wire contract changed");
    else assert(row.score.infrastructureError,"Missing baseline request without a recorded transport failure");
  }
  for(const record of row.modelRecords){
    assert.equal(record.contextSha256,row.contextSha256);assert.equal(record.request.model,metadata.model);
    assert.equal(record.request.temperature,metadata.temperature);
    assert.equal(record.request.messages.filter(m=>m.role==="system").length,1);
    assert(record.request.messages[0].content.includes(sharedContext(c,sealed.frozen)));
    assert(!Object.keys(record).some(k=>/authorization|api.?key/i.test(k)));
  }
}
console.log(JSON.stringify({runId:metadata.runId,auditedCaseGroups:40,unseenCaseCount:20,disjointPOIs:sealed.disjointPlaces,
  v10JarAnd80SourcesUnchanged:true,independentRescoringIdentical:true,wholeTaskReruns:0}));
