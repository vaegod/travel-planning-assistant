import test from "node:test";
import assert from "node:assert/strict";
import {verifyBudgetProof} from "../../scripts/agent-eval/budget-proof.mjs";
const c={input:{days:3,budget:100}};
function row(){return {group:"agent",modelCalls:0,modelRecords:[],toolCalls:0,observation:{plan:null,job:{
  status:"REFUSED",modelRounds:0,errorCode:null,plan:null,
  refusal:{code:"BUDGET_BELOW_MINIMUM",minimumExpenses:{policy:"destination-fixed-expenses-v1",
    accommodation:400,food:240,other:60,total:700,requestedBudget:100}},
  traces:[{tool:"check_budget",status:"refused"}],validation:{valid:false,estimatedTotal:700,remainingBudget:-600}}}};}
test("zero-model budget refusal requires an independently checkable policy witness",()=>{
  assert.equal(verifyBudgetProof(c,row()),true);
});
test("zero-model witness rejects invented arithmetic and a feasible budget",()=>{
  const altered=row();altered.observation.job.refusal.minimumExpenses.total=600;
  assert.throws(()=>verifyBudgetProof(c,altered));
  assert.throws(()=>verifyBudgetProof({input:{days:3,budget:1000}},row()));
});
test("zero-model witness cannot excuse a generic failure, saved plan or missing trace",()=>{
  for(const change of [r=>r.observation.job.status="ERROR",r=>r.observation.plan={success:true},
    r=>r.observation.job.traces=[],r=>r.observation.error={code:"MODEL_TIMEOUT"},r=>r.group="one_shot"]){
    const altered=row();change(altered);assert.throws(()=>verifyBudgetProof(c,altered));
  }
});
