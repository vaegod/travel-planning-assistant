import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fixturePlan, hash, sharedContext } from "../../scripts/agent-eval/protocol.mjs";
import { scoreCase } from "./score.mjs";

const dataset=JSON.parse(await readFile(new URL("./dataset.json",import.meta.url)));
const pilot=JSON.parse(await readFile(new URL("./pilot.json",import.meta.url)));
const frozen=JSON.parse(await readFile(new URL("./frozen-sandbox.json",import.meta.url)));

test("full dataset has 30 sourced cases, 8 revisions, 2 refusals and an isolated 30/10 split",()=>{
  assert.equal(dataset.cases.length,40);
  const sourced=dataset.cases.filter(c=>c.provenance.source==="ChinaTravel/easy");
  assert.equal(sourced.length,30);
  assert.equal(new Set(sourced.map(c=>c.provenance.sourceUid)).size,30);
  assert.equal(dataset.cases.filter(c=>c.kind==="revise").length,8);
  assert.equal(dataset.cases.filter(c=>c.expectedOutcome==="refused").length,2);
  assert.equal(dataset.cases.filter(c=>c.split==="development").length,30);
  assert.equal(dataset.cases.filter(c=>c.split==="holdout").length,10);
  for(const c of pilot.cases){
    assert.equal(c.split,"development");
    assert.deepEqual(c,dataset.cases.find(x=>x.id===c.id));
  }
  assert.deepEqual(new Set(sourced.map(c=>c.input.travelMode)),new Set(["walking","transit","driving"]));
  assert.deepEqual(new Set(sourced.map(c=>c.input.days)),new Set([1,2,3]));
});
test("all 38 positive tasks have a feasible complete plan under the independent scorer",()=>{
  for(const c of dataset.cases.filter(c=>c.expectedOutcome==="completed")){
    const score=scoreCase(c,{plan:fixturePlan(c,frozen),patchScopeValid:true},frozen);
    assert.equal(score.taskPassed,true,c.id+":"+score.failures);
  }
});
test("upstream snapshots and per-case provenance match their recorded identifiers",async()=>{
  for(const [name,digest]of Object.entries(dataset.sourceFileSha256))
    assert.equal(hash(await readFile(new URL("./source/"+name,import.meta.url))),digest,name);
  for(const c of dataset.cases.filter(c=>c.provenance.source==="ChinaTravel/easy")){
    assert.equal(c.provenance.sourceUid,c.provenance.sourceRow.uid);
    assert.equal(c.input.city,c.provenance.sourceRow.target_city);
    assert.equal(c.input.days,Number(c.provenance.sourceRow.days));
    assert(!sharedContext(c,frozen).includes("hard_logic_py"));
  }
});
test("negative tasks have explicit infeasibility proofs and accept only visible reasons",()=>{
  const budget=dataset.cases.find(c=>c.id==="B09");
  assert(budget.input.budget<200*(budget.input.days-1)+100*budget.input.days);
  const route=dataset.cases.find(c=>c.id==="B10");
  assert.equal(JSON.parse(sharedContext(route,frozen).split("固定参考数据（查询词不限制候选召回，不测真实搜索排序）：\n")[1]).routes.length,0);
  for(const c of [budget,route]){
    assert(c.expect.proof);
    const plan=fixturePlan(c,frozen);
    assert.equal(scoreCase(c,{plan,visibleText:plan.error},frozen).correctRefusal,true);
    assert.equal(scoreCase(c,{plan:null,error:{code:"MODEL_TIMEOUT"},visibleText:"模型请求失败"},frozen).correctRefusal,false);
  }
});
