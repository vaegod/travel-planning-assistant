import assert from "node:assert/strict";

// The shared task contract already declares these project assumptions for both groups.
// A zero-model result is allowed only with a verifiable deterministic refusal witness.
export function verifyBudgetProof(c,row){
  assert.equal(row.group,"agent");assert.equal(row.modelCalls,0);
  assert.equal(row.modelRecords.length,0);assert.equal(row.toolCalls,0);
  const job=row.observation.job;
  assert.equal(job.status,"REFUSED");assert.equal(job.modelRounds,0);
  assert.equal(job.errorCode,null);assert.equal(row.observation.error,undefined);
  assert.equal(row.observation.plan,null);assert.equal(job.plan,null);
  assert.equal(job.refusal.code,"BUDGET_BELOW_MINIMUM");
  const accommodation=Math.max(0,c.input.days-1)*200,food=c.input.days*80,other=c.input.days*20;
  const total=accommodation+food+other;
  assert(c.input.budget<total,"Budget is feasible under the declared fixed-expense policy");
  assert.deepEqual(job.refusal.minimumExpenses,{policy:"destination-fixed-expenses-v1",
    accommodation,food,other,total,requestedBudget:c.input.budget});
  assert.equal(job.validation.valid,false);assert.equal(job.validation.estimatedTotal,total);
  assert.equal(job.validation.remainingBudget,c.input.budget-total);
  assert(job.traces.some(t=>t.tool==="check_budget"&&t.status==="refused"));
  return true;
}
