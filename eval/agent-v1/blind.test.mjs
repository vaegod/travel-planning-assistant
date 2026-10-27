import test from "node:test";
import assert from "node:assert/strict";
import {readFile,mkdir} from "node:fs/promises";
import {resolve} from "node:path";
import {randomUUID} from "node:crypto";
import {fileURLToPath} from "node:url";
import {spawnSync} from "node:child_process";
import {BLIND_FOLDER,verifyBlindSet,claimBlindRun,requireUnexposedBlindSet,verifyBaselineConfiguration} from "../../scripts/agent-eval/blind-contract.mjs";
const root=fileURLToPath(new URL("../../",import.meta.url));
// Static data integrity in CI does not require a bit-identical locally built v10 JAR.
const sealed=await verifyBlindSet(root,{runtime:false});
test("blind set is sealed, feasible and disjoint from the untouched regression cases",()=>{
  assert.equal(sealed.dataset.cases.length,20);assert.equal(sealed.disjointPlaces,80);
  assert.equal(sealed.dataset.cases.filter(c=>c.kind==="revise").length,4);
  assert.equal(sealed.dataset.cases.filter(c=>c.expectedOutcome==="refused").length,2);
  assert(sealed.dataset.cases.some(c=>c.input.days===4));
});
test("single-use blind exposure is atomic and blocks another run",async()=>{
  const isolated=resolve(root,".runtime","blind-claim-test-"+randomUUID());
  await mkdir(resolve(isolated,BLIND_FOLDER),{recursive:true});
  await requireUnexposedBlindSet(isolated);
  await claimBlindRun(isolated,"first-run");
  await assert.rejects(()=>claimBlindRun(isolated,"another-run"),{code:"EEXIST"});
  await assert.rejects(()=>requireUnexposedBlindSet(isolated),/already been exposed/);
});
test("blind execution retains the exact v10 runtime, model request and scoring blocks",async()=>{
  const original=await readFile(resolve(root,"scripts/run-agent-eval.mjs"),"utf8");
  const blind=await readFile(resolve(root,"scripts/run-agent-blind-eval.mjs"),"utf8");
  const block=(s,a,b)=>{
    const start=s.indexOf(a),end=s.indexOf(b,start+a.length);
    assert(start>=0&&end>start);return s.slice(start,end);
  };
  assert.equal(block(blind,"async function ready()","try{\n  await ready();"),
    block(original,"async function ready()","try{\n  await ready();"));
  assert.equal(block(blind,"const java=settings.JAVA_HOME","async function ready()"),
    block(original,"const java=settings.JAVA_HOME","async function ready()"));
  assert.equal(block(blind,"  let index=0;","}catch(error){"),
    block(original,"  let index=0;","}catch(error){"));
});
test("blind report uses the same metric aggregation without converting old data into paired results",async()=>{
  const original=await readFile(resolve(root,"scripts/report-agent-eval.mjs"),"utf8");
  const blind=await readFile(resolve(root,"scripts/report-agent-blind-eval.mjs"),"utf8");
  const block=s=>s.slice(s.indexOf("function summarize(items)"),s.indexOf("const groups={}"));
  assert.equal(block(original),block(blind));
  assert(blind.includes("pairedCases:false"));assert(blind.includes("const expected=40"));
});
test("blind evaluation rejects fixture mode before any model or Agent output",()=>{
  const result=spawnSync(process.execPath,["scripts/run-agent-blind-eval.mjs"],{cwd:root,encoding:"utf8"});
  assert.notEqual(result.status,0);assert(result.stderr.includes("Blind cases cannot be used for fixture/pilot debugging"));
});
test("v10 configuration lock rejects changing model, pacing or an existing source",()=>{
  const baseline=sealed.baseline;const good=structuredClone(baseline.metadata);
  assert.doesNotThrow(()=>verifyBaselineConfiguration(good,baseline));
  for(const change of [
    x=>x.model="changed",x=>x.pacing.minIntervalMs=1,
    x=>x.sourceFileSha256["scripts/agent-eval/protocol.mjs"]="changed",
    x=>x.jarSha256="changed",x=>x.modelRecoveryPolicy.maxRecoveryAttemptsPerJob=5,
  ]){const bad=structuredClone(good);change(bad);assert.throws(()=>verifyBaselineConfiguration(bad,baseline));}
});
