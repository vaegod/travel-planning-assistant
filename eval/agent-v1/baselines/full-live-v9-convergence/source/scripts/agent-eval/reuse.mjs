// Narrow migration: fix revision-only baseline output, keep exact unchanged requests.
import assert from "node:assert/strict";
import {isDeepStrictEqual} from "node:util";
import {hash} from "./protocol.mjs";
export function verifyRevisionOnlyMigration(previous,current,oldProtocol,newProtocol,oldRunner,newRunner){
  assert.equal(previous.protocolVersion,"1.2-paced-upstream");
  assert.equal(current.protocolVersion,"1.3-revision-patch-output");
  assert(!previous.invalidReason,"Invalid experiments cannot supply cached requests");
  for(const field of ["mode","model","temperature","enableThinking","maxOutputTokens","concurrency","pacing",
    "jarSha256","datasetSha256","frozenSha256","queryRevision","sandboxRevision"])
    assert.deepEqual(previous[field],current[field],"Migration changes "+field);
  for(const [file,digest]of Object.entries(previous.sourceFileSha256)){
    if(["scripts/agent-eval/protocol.mjs","scripts/run-agent-eval.mjs"].includes(file))continue;
    assert.equal(current.sourceFileSha256[file],digest,"Migration changes immutable code: "+file);
  }
  assert.equal(hash(oldProtocol),previous.sourceFileSha256["scripts/agent-eval/protocol.mjs"],"Untrusted old protocol snapshot");
  assert.equal(hash(oldRunner),previous.sourceFileSha256["scripts/run-agent-eval.mjs"],"Untrusted old runner snapshot");
  const removeBaseline=source=>{
    const a=source.indexOf("export function baselinePayload("),b=source.indexOf("export function fixturePlan(");
    assert(a>=0&&b>a);return source.slice(0,a)+source.slice(b);
  };
  assert.equal(removeBaseline(oldProtocol),removeBaseline(newProtocol),"Shared context, wire adapter or fixtures changed");
  const execution=source=>{
    const a=source.indexOf("async function baseline(c){"),b=source.indexOf("async function finishResult(");
    assert(a>=0&&b>a);return source.slice(a,b);
  };
  assert.equal(execution(oldRunner),execution(newRunner),"Model delivery or agent execution changed");
}
export function reusablePair(rows,c,contextSha256,expectedOneShot){
  // Rerun every revision, including any old successes; never select by its score.
  if(c.kind==="revise")return [];
  const pair=["one_shot","agent"].map(group=>rows.find(r=>r.caseId===c.id&&r.group===group));
  if(pair.some(r=>!r))return [];
  for(const row of pair){
    assert.equal(row.caseSha256,hash(c));
    assert.equal(row.contextSha256,contextSha256);
  }
  if(!pair[0].modelRecords?.[0])return [];
  assert(isDeepStrictEqual(pair[0].modelRecords[0].request,expectedOneShot),"Unchanged-case request differs: "+c.id);
  return pair;
}
