import test from "node:test";
import assert from "node:assert/strict";
import {recoveryEvidence,verifyRecovery,summarizeRecovery} from "../../scripts/agent-eval/model-recovery.mjs";
const id=i=>"00000000-0000-0000-0000-"+String(i).padStart(12,"0");
function recovered(passed=true,capturedFirst=true) {
  const entries=[
    {attemptId:id(1),modelRound:1,status:"MODEL_TIMEOUT",durationMs:75000,recoveryOf:null,phase:"awaiting_response",requestSent:true},
    {attemptId:id(2),modelRound:2,status:"success",durationMs:1200,recoveryOf:id(1),usageComplete:true,inputTokens:10,outputTokens:2},
  ];
  const row={group:"agent",score:{taskPassed:passed},observation:{job:{modelRounds:2,recoveryAttempts:1,
    recoveryDurationMs:1200,modelCalls:entries}},modelRecords:entries.filter((e,i)=>capturedFirst||i===1)
    .map(e=>({clientAttemptId:e.attemptId,recoveryOf:e.recoveryOf,request:{model:"same",messages:["same"]}}))};
  row.recovery=recoveryEvidence(row);return row;
}
test("recovery metrics retain first failure, extra latency and actual capture coverage",()=>{
  const row=recovered();
  assert(verifyRecovery(row));assert.equal(row.recovery.firstAttemptPassed,false);
  assert.equal(row.recovery.recoveredPassed,true);assert.equal(row.recovery.extraDurationMs,1200);
  assert.equal(row.recovery.failedInitialDurationMs,75000);assert.equal(row.recovery.extraCapturedRequests,1);
  assert(verifyRecovery(recovered(true,false)),"Uncaptured initial connection failure must remain an attempted call");
  const s=summarizeRecovery([row,recovered(false)]);
  assert.equal(s.firstAttemptSuccessRate,0);assert.equal(s.afterRecoverySuccessRate,.5);
  assert.equal(s.extraModelAttempts,2);assert.equal(s.extraDurationMs,2400);
});
test("audit rejects hidden attempts, changed replay input, forged costs and duplicate recovery",()=>{
  for(const change of [
    r=>r.observation.job.modelRounds++,
    r=>r.modelRecords[1].request.messages.push("different"),
    r=>r.observation.job.recoveryDurationMs=0,
    r=>r.recovery.firstAttemptPassed=true,
    r=>r.observation.job.recoveryAttempts=2,
  ]) { const row=recovered();change(row);assert.throws(()=>verifyRecovery(row)); }
});
test("baseline and deterministic zero-model tasks keep their original denominator and zero recovery",()=>{
  const legacy={group:"agent",score:{taskPassed:true},modelRecords:[],observation:{job:{modelRounds:0,
    modelCalls:[],recoveryAttempts:0,recoveryDurationMs:0}}};
  legacy.recovery=recoveryEvidence(legacy);assert(verifyRecovery(legacy));
  const summary=summarizeRecovery([legacy,{group:"one_shot",score:{taskPassed:false},modelRecords:[],observation:{}}]);
  assert.equal(summary.firstAttemptSuccessRate,.5);assert.equal(summary.afterRecoverySuccessRate,.5);
  assert.equal(summary.extraModelAttempts,0);assert.equal(summary.extraKnownInputTokens,null);
});
