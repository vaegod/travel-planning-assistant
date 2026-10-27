import test from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {reusablePair,verifyRevisionOnlyMigration} from "../../scripts/agent-eval/reuse.mjs";
import {hash,clone} from "../../scripts/agent-eval/protocol.mjs";
const c={id:"P1",kind:"plan",input:{city:"杭州"}};
const request={model:"same-model",messages:[{role:"user",content:"same-request"}]};
const rows=["one_shot","agent"].map(group=>({caseId:c.id,group,caseSha256:hash(c),contextSha256:"facts",
  score:{taskPassed:false},modelRecords:[{request}]}));
test("unchanged failed pairs are reusable; changed requests or facts are rejected",()=>{
  assert.equal(reusablePair(rows,c,"facts",request).length,2);
  assert.throws(()=>reusablePair(rows,c,"other",request));
  assert.throws(()=>reusablePair(rows,c,"facts",{...request,model:"other"}));
});
test("all revisions are rerun irrespective of success and incomplete pairs are not reused",()=>{
  assert.deepEqual(reusablePair(rows,{...c,kind:"revise"},"facts",request),[]);
  assert.deepEqual(reusablePair(rows.slice(0,1),c,"facts",request),[]);
});
test("migration rejects changed Java, pacing, shared instructions and execution functions",async()=>{
  const oldProtocol=await readFile(new URL("./results/full-live-v2/protocol-source.mjs",import.meta.url),"utf8");
  const oldRunner=await readFile(new URL("./results/full-live-v2/runner-source.mjs",import.meta.url),"utf8");
  const before={protocolVersion:"1.2-paced-upstream",mode:"live",pacing:{tokenBudget:40000},
    sourceFileSha256:{"scripts/agent-eval/protocol.mjs":hash(oldProtocol),"scripts/run-agent-eval.mjs":hash(oldRunner),"agent.java":"same"}};
  const after={...clone(before),protocolVersion:"1.3-revision-patch-output"};
  assert.doesNotThrow(()=>verifyRevisionOnlyMigration(before,after,oldProtocol,oldProtocol,oldRunner,oldRunner));
  const changed=clone(after);changed.sourceFileSha256["agent.java"]="different";
  assert.throws(()=>verifyRevisionOnlyMigration(before,changed,oldProtocol,oldProtocol,oldRunner,oldRunner));
  assert.throws(()=>verifyRevisionOnlyMigration(before,{...after,pacing:{tokenBudget:50000}},oldProtocol,oldProtocol,oldRunner,oldRunner));
  assert.throws(()=>verifyRevisionOnlyMigration(before,after,oldProtocol,oldProtocol+"\nchanged shared policy",oldRunner,oldRunner));
  assert.throws(()=>verifyRevisionOnlyMigration(before,after,oldProtocol,oldProtocol,oldRunner,oldRunner.replace("async function baseline(c){","async function baseline(c){changed();")));
});
