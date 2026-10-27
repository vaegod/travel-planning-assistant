import assert from "node:assert/strict";
import {readFile,writeFile} from "node:fs/promises";
import {resolve} from "node:path";
import {hash,fixturePlan,sharedContext} from "./protocol.mjs";
import {scoreCase} from "../../eval/agent-v1/score.mjs";
export const BLIND_FOLDER="eval/agent-v1/blind-v1";
export const V10_RUN="full-live-v10-reliability-final";
const read=async path=>JSON.parse(await readFile(path,"utf8"));
const key=name=>name.replace(/[！-～]/gu,c=>String.fromCharCode(c.charCodeAt(0)-0xfee0)).replace(/\s/gu,"");
export async function verifyV10Source(root,seal,{runtime=true}={}) {
  const baseline=await read(resolve(root,"eval/agent-v1/results",V10_RUN,"results.json"));
  assert.equal(hash(await readFile(resolve(root,"eval/agent-v1/results",V10_RUN,"results.json"))),seal.baselineResultsSha256);
  assert.deepEqual(seal.v10SourceFileSha256,baseline.metadata.sourceFileSha256);
  if(runtime){
    for(const [file,digest]of Object.entries(seal.v10SourceFileSha256))
      assert.equal(hash(await readFile(resolve(root,file))),digest,"v10 source changed: "+file);
    assert.equal(hash(await readFile(resolve(root,"travel-java-server/target/travel-java-server-0.0.1-SNAPSHOT.jar"))),seal.v10JarSha256);
  }
  assert.equal(seal.v10JarSha256,baseline.metadata.jarSha256);
  return baseline;
}
export async function verifyBlindSet(root,{runtime=true,sourceRoot=root}={}) {
  const folder=resolve(root,BLIND_FOLDER);
  const plan=await read(resolve(folder,"plan.json")),seal=await read(resolve(folder,"seal.json"));
  const datasetBytes=await readFile(resolve(folder,"dataset.json")),frozenBytes=await readFile(resolve(folder,"frozen-sandbox.json"));
  assert.equal(hash(datasetBytes),seal.datasetSha256);assert.equal(hash(frozenBytes),seal.frozenSha256);
  assert.equal(hash(await readFile(resolve(folder,"plan.json"))),seal.planSha256);
  assert.equal(hash(await readFile(resolve(sourceRoot,"scripts/build-agent-blind.py"))),seal.builderSha256);
  assert.equal(plan.baselineRun,V10_RUN);assert.equal(seal.baselineRun,V10_RUN);
  const baseline=await verifyV10Source(root,seal,{runtime});
  const previousBytes=await readFile(resolve(root,"eval/agent-v1/dataset.json"));
  const previousFrozenBytes=await readFile(resolve(root,"eval/agent-v1/frozen-sandbox.json"));
  assert.equal(hash(previousBytes),seal.regressionDatasetSha256);
  assert.equal(hash(previousFrozenBytes),seal.regressionFrozenSha256);
  const previous=JSON.parse(previousBytes),oldFrozen=JSON.parse(previousFrozenBytes);
  const dataset=JSON.parse(datasetBytes),frozen=JSON.parse(frozenBytes);
  assert.equal(dataset.phase,"blind");assert.equal(dataset.cases.length,20);
  assert.equal(new Set(dataset.cases.map(c=>c.id)).size,20);
  assert(dataset.cases.every(c=>c.split==="blind"&&!c.pilot));
  assert.equal(dataset.cases.filter(c=>c.kind==="revise").length,4);
  assert.equal(dataset.cases.filter(c=>c.expectedOutcome==="refused").length,2);
  const sourced=dataset.cases.filter(c=>c.provenance.source==="ChinaTravel/human");
  assert.equal(sourced.length,14);assert.equal(new Set(sourced.map(c=>c.provenance.sourceUid)).size,14);
  const usedUids=new Set(previous.cases.map(c=>c.provenance.sourceUid));
  for(const c of sourced) {
    assert(!usedUids.has(c.provenance.sourceUid));
    assert.equal(c.provenance.sourceUid,c.provenance.sourceRow.uid);
    assert.equal(c.input.city,c.provenance.sourceRow.target_city);
    assert.equal(c.input.days,Number(c.provenance.sourceRow.days));
    assert.equal(c.input.budget,1000*c.input.days);
    assert(!sharedContext(c,frozen).includes("hard_logic_py"));
  }
  for(const [name,digest]of Object.entries(dataset.sourceFileSha256))
    assert.equal(hash(await readFile(resolve(root,"eval/agent-v1/source",name))),digest);
  assert.deepEqual(dataset.sourceFileSha256,previous.sourceFileSha256);
  assert.deepEqual(frozen.routeFormula,oldFrozen.routeFormula);
  assert.deepEqual(frozen.costAssumptions,oldFrozen.costAssumptions);
  let places=0;
  for(const [city,data]of Object.entries(frozen.cities)) {
    const old=oldFrozen.cities[city].places;
    const ids=new Set(old.map(p=>p.sourceRowId)),names=new Set(old.map(p=>key(p.name)));
    const coords=new Set(old.map(p=>p.latitude.toFixed(6)+","+p.longitude.toFixed(6)));
    assert.equal(data.places.length,8);assert.equal(data.routes.length,8*7*3);
    assert.equal(new Set(data.places.map(p=>p.id)).size,8);
    for(const p of data.places) {
      assert(!ids.has(p.sourceRowId),"Old POI row reused");
      assert(!names.has(key(p.name)),"Old POI name reused");
      assert(!coords.has(p.latitude.toFixed(6)+","+p.longitude.toFixed(6)),"Old coordinate reused");
    }
    assert(data.routes.filter(r=>r.profile==="walking").every(r=>Math.ceil(r.seconds/60)<=25));
    places+=data.places.length;
  }
  for(const c of dataset.cases) {
    assert(!previous.cases.some(p=>hash(p.input)===hash(c.input)),"Exact prior input reused");
    if(c.expectedOutcome==="completed") {
      const scored=scoreCase(c,{plan:fixturePlan(c,frozen),patchScopeValid:true},frozen);
      assert(scored.taskPassed,c.id+":"+scored.failures);
    } else if(c.expect.refusalReason==="budget") {
      assert(c.input.budget<200*(c.input.days-1)+100*c.input.days);
    } else {
      assert(c.environment?.noTransit&&c.input.travelMode==="transit");
    }
  }
  return {dataset,frozen,seal,plan,baseline,disjointPlaces:places};
}
export async function claimBlindRun(root,runId) {
  assert(/^[a-zA-Z0-9_-]+$/.test(runId));
  const file=resolve(root,BLIND_FOLDER,"exposure.json");
  await writeFile(file,JSON.stringify({runId,exposedAt:new Date().toISOString(),
    meaning:"First formal model execution starts now. These cases cease to be an unseen holdout after this run.",
    attemptsPerCaseGroup:1,wholeTaskReruns:false},null,2)+"\n",{flag:"wx"});
}
export async function requireUnexposedBlindSet(root) {
  try {
    await readFile(resolve(root,BLIND_FOLDER,"exposure.json"));
    assert.fail("These blind cases have already been exposed; refusing another run");
  } catch(error) { if(error.code!=="ENOENT")throw error; }
}
export function verifyBaselineConfiguration(metadata,baseline) {
  for(const field of ["model","temperature","enableThinking","maxOutputTokens","concurrency","pacing",
    "protocolVersion","modelRecoveryPolicy","relayTransportPolicy","visitRequirementPolicy","ticketRequirementPolicy"])
    assert.equal(hash(metadata[field]),hash(baseline.metadata[field]),"Blind evaluation changes v10 "+field);
  assert.equal(metadata.jarSha256,baseline.metadata.jarSha256);
  for(const [file,digest]of Object.entries(baseline.metadata.sourceFileSha256))
    assert.equal(metadata.sourceFileSha256[file],digest,"Blind evaluation changes v10 source "+file);
}
