import assert from "node:assert/strict";
const sum=(items,key)=>items.reduce((n,item)=>n+(Number.isFinite(item[key])?item[key]:0),0);
export function recoveryEvidence(row) {
  const entries=row.observation.job?.modelCalls||[];
  const extra=entries.filter(e=>e.recoveryOf);
  const captured=row.modelRecords.filter(r=>r.recoveryOf);
  const failedInitial=entries.filter(e=>extra.some(r=>r.recoveryOf===e.attemptId));
  const metered=extra.filter(e=>e.usageComplete);
  return {
    usedRecovery:extra.length>0,firstAttemptPassed:row.score.taskPassed&&extra.length===0,
    afterRecoveryPassed:row.score.taskPassed,recoveredPassed:row.score.taskPassed&&extra.length>0,
    extraModelAttempts:extra.length,extraCapturedRequests:captured.length,
    extraForwardedRequests:captured.filter(r=>r.upstreamStartedAt).length,
    extraDurationMs:sum(extra,"durationMs"),failedInitialDurationMs:sum(failedInitial,"durationMs"),
    extraUsageKnownCalls:metered.length,extraKnownInputTokens:metered.length?sum(metered,"inputTokens"):null,
    extraKnownOutputTokens:metered.length?sum(metered,"outputTokens"):null,
    initialFailures:failedInitial.map(e=>({attemptId:e.attemptId,status:e.status,phase:e.phase,exceptionType:e.exceptionType,
      connectionReused:e.connectionReused,requestSent:e.requestSent,responseStarted:e.responseStarted,httpStatus:e.httpStatus})),
  };
}
export function verifyRecovery(row) {
  assert.deepEqual(row.recovery,recoveryEvidence(row),"Recovery accounting differs from physical evidence");
  if(row.group!=="agent") {
    assert.equal(row.recovery.extraModelAttempts,0);return true;
  }
  const job=row.observation.job;
  assert(job,"Agent job evidence missing");
  const entries=job.modelCalls,extra=entries.filter(e=>e.recoveryOf);
  assert.equal(entries.length,job.modelRounds,"Every physical attempt, including a disconnected one, needs a trace");
  assert.equal(new Set(entries.map(e=>e.attemptId)).size,entries.length);
  assert(extra.length<=1);assert.equal(job.recoveryAttempts,extra.length);
  assert.equal(job.recoveryDurationMs,sum(extra,"durationMs"));
  for(const [i,e]of entries.entries()) {
    assert.match(e.attemptId,/^[0-9a-f-]{36}$/i);assert.equal(e.modelRound,i+1);
    assert(Number.isFinite(e.durationMs)&&e.durationMs>=0);
    if(e.recoveryOf) {
      const prior=entries[i-1];assert(prior);assert.equal(prior.attemptId,e.recoveryOf);
      assert(["MODEL_CONNECTION_ERROR","MODEL_TIMEOUT","MODEL_UNAVAILABLE"].includes(prior.status));
      const original=row.modelRecords.find(r=>r.clientAttemptId===prior.attemptId);
      const recovered=row.modelRecords.find(r=>r.clientAttemptId===e.attemptId);
      if(original&&recovered)assert.deepEqual(recovered.request,original.request,"Recovery changes the pending generation");
    }
    if(e.status==="success")assert(row.modelRecords.some(r=>r.clientAttemptId===e.attemptId),"Successful request not captured");
  }
  for(const record of row.modelRecords) {
    const entry=entries.find(e=>e.attemptId===record.clientAttemptId);
    assert(entry,"Captured request has no physical attempt trace");
    assert.equal(record.recoveryOf||null,entry.recoveryOf||null);
  }
  return true;
}
export function summarizeRecovery(rows) {
  const facts=rows.map(recoveryEvidence);
  const count=key=>facts.filter(x=>x[key]).length;
  const ratio=n=>rows.length?n/rows.length:null;
  return {
    tasks:rows.length,firstAttemptPassed:count("firstAttemptPassed"),
    firstAttemptSuccessRate:ratio(count("firstAttemptPassed")),
    afterRecoveryPassed:count("afterRecoveryPassed"),afterRecoverySuccessRate:ratio(count("afterRecoveryPassed")),
    recoveryTasks:count("usedRecovery"),recoveredTasksPassed:count("recoveredPassed"),
    recoverySuccessRate:count("usedRecovery")?count("recoveredPassed")/count("usedRecovery"):null,
    failedAfterRecovery:facts.filter(x=>x.usedRecovery&&!x.afterRecoveryPassed).length,
    extraModelAttempts:sum(facts,"extraModelAttempts"),extraCapturedRequests:sum(facts,"extraCapturedRequests"),
    extraForwardedRequests:sum(facts,"extraForwardedRequests"),
    extraDurationMs:sum(facts,"extraDurationMs"),failedInitialDurationMs:sum(facts,"failedInitialDurationMs"),
    extraUsageKnownCalls:sum(facts,"extraUsageKnownCalls"),
    extraKnownInputTokens:facts.some(x=>x.extraUsageKnownCalls)?sum(facts,"extraKnownInputTokens"):null,
    extraKnownOutputTokens:facts.some(x=>x.extraUsageKnownCalls)?sum(facts,"extraKnownOutputTokens"):null,
  };
}
