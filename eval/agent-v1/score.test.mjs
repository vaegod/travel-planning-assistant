import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fixturePlan, sharedContext, clone, wirePayload, baselinePayload } from "../../scripts/agent-eval/protocol.mjs";
import { scoreCase, deliverBaseline } from "./score.mjs";

const dataset=JSON.parse(await readFile(new URL("./pilot.json",import.meta.url)));
const frozen=JSON.parse(await readFile(new URL("./frozen-sandbox.json",import.meta.url)));
const c=dataset.cases[0];
const obs=plan=>({plan,visibleText:JSON.stringify(plan),patchScopeValid:true});
test("independent scorer accepts feasibility witnesses across all pilot cities",()=>{
  for(const c of dataset.cases){
    const score=scoreCase(c,obs(fixturePlan(c,frozen)),frozen);
    assert.equal(score.taskPassed,true,c.id+":"+score.failures);
  }
});
test("budget, price, mode, duplicates, source and time violations are detected",()=>{
  const mutations=[
    [p=>{p.budgetBreakdown.food=100000;},"budgetValid"],
    [p=>{p.budgetBreakdown.accommodation=-1;},"budgetValid"],
    [p=>{p.budgetBreakdown.food=1.001;},"budgetValid"],
    [p=>{p.travelMode="driving";},"travelMode"],
    [p=>{p.dailyItinerary[0].afternoon=clone(p.dailyItinerary[0].morning);},"distinctPlaces"],
    [p=>{p.dailyItinerary[0].morning.placeId="unseen";},"groundedPlaces"],
    [p=>{p.dailyItinerary[0].morning.visitMinutes=900;},"visitAndTravelValues"],
    [p=>{p.dailyItinerary[0].afternoon.travelMinutes=0;},"travelNotUnderestimated"],
    [p=>{p.dailyItinerary[0].afternoon.route.fromId="wrong";},"reportedRouteConsistent"],
    [p=>{p.dailyItinerary[0].morning.visitMinutes=360;p.dailyItinerary[0].afternoon.visitMinutes=360;},"dailyTimeUsingReferenceRoutes"],
  ];
  for(const [change,key] of mutations){
    const plan=fixturePlan(c,frozen);change(plan);
    assert.equal(scoreCase(c,obs(plan),frozen).checks[key],false,key);
  }
});
test("missing delivery and wrong refusal cannot inflate positive task success",()=>{
  const score=scoreCase(c,{plan:null,error:{code:"MODEL_TIMEOUT"}},frozen);
  assert.equal(score.taskPassed,false);assert.equal(score.infrastructureError,true);
  assert.equal(scoreCase(c,obs({success:false,error:"预算不足"}),frozen).taskPassed,false);
});
test("correct refusal, generic failure and hidden model refusal are distinct",()=>{
  const negative={...clone(c),expectedOutcome:"refused",expect:{refusalPattern:"预算"}};
  assert.equal(scoreCase(negative,obs({success:false,error:"预算不足"}),frozen).correctRefusal,true);
  const hidden=scoreCase(negative,{plan:null,visibleText:"未提交有效草稿",modelFinalText:"预算不足"},frozen);
  assert.equal(hidden.correctRefusal,false);assert.equal(hidden.modelRefusalObserved,true);
  const network=scoreCase({...negative,expect:{refusalPattern:"公交|无法"}},
    {plan:null,visibleText:"无法连接模型服务",error:{code:"MODEL_CONNECTION_ERROR"}},frozen);
  assert.equal(network.correctRefusal,false);assert.equal(network.outcome,"infrastructure_error");
});
test("baseline malformed output is preserved as failure without repair",()=>{
  assert.equal(deliverBaseline(c,'{"success":true').plan,null);
  assert.equal(deliverBaseline(c,'<think>text</think>{"success":true}').plan,null);
});
test("a changed neighbor route is independently detected after local activity replacement",()=>{
  const revision={...clone(c),kind:"revise",revision:{day:1,slot:"morning"},basePlan:fixturePlan(c,frozen)};
  const replacement=frozen.cities[c.input.city].places[4];
  revision.expect.requiredPlaceId=replacement.id;
  const patch={morning:{...clone(revision.basePlan.dailyItinerary[0].morning),spot:replacement.name,placeId:replacement.id,
    estimatedCost:replacement.ticketBudget},budgetBreakdown:clone(revision.basePlan.budgetBreakdown)};
  patch.budgetBreakdown.tickets=replacement.ticketBudget+revision.basePlan.dailyItinerary[0].afternoon.estimatedCost;
  const value=deliverBaseline(revision,JSON.stringify(patch));
  const score=scoreCase(revision,{...value,visibleText:""},frozen);
  assert.equal(score.checks.revisionScope,true);
  assert.equal(score.checks.reportedRouteConsistent,false);
  patch.extra="out of scope";
  assert.equal(deliverBaseline(revision,JSON.stringify(patch)).patchScopeValid,false);
});
test("revision scope ignores null defaults added by the API but rejects real content edits",()=>{
  const revision={...clone(c),kind:"revise",revision:{day:1,slot:"afternoon"},basePlan:fixturePlan(c,frozen)};
  const plan=clone(revision.basePlan);
  plan.dailyItinerary[0].morning.category=null;
  plan.dailyItinerary[0].morning.rest=null;
  plan.dailyItinerary[0].evening.indoor=null;
  assert.equal(scoreCase(revision,obs(plan),frozen).checks.revisionScope,true);
  plan.dailyItinerary[0].morning.description="silently changed";
  assert.equal(scoreCase(revision,obs(plan),frozen).checks.revisionScope,false);
});
test("common evidence does not contain hidden labels or executable source constraints",()=>{
  for(const c of dataset.cases){
    const context=sharedContext(c,frozen);
    assert(!context.includes("expectedOutcome"));assert(!context.includes("hard_logic_py"));
    assert(!context.includes("sourceUid"));assert(!context.includes("basePlan"));
  }
});
test("shared rules specify seconds-to-minutes conversion and budget upper-limit semantics",()=>{
  const context=sharedContext(c,frozen);
  assert(context.includes("ceil(seconds / 60)"));
  assert(context.includes("totalBudget必须等于任务的budget上限"));
  const p=fixturePlan(c,frozen);
  p.totalBudget=p.estimatedTotal;
  assert.equal(scoreCase(c,obs(p),frozen).checks.identity,false);
});
test("revision baseline system requires a patch without conflicting full-itinerary instruction",()=>{
  const revision={...clone(c),kind:"revise",revision:{day:1,slot:"morning",instruction:"replace"},basePlan:fixturePlan(c,frozen)};
  const payload=baselinePayload(revision,"model",4096,false);
  assert(payload.messages[0].content.includes("本次只输出局部修改补丁"));
  assert(!payload.messages[0].content.includes("满足任务时输出完整行程"));
  assert(payload.messages[0].content.includes("不得输出success:true、dailyItinerary"));
});
test("model wire format has one leading system message and retains original policy plus shared facts",()=>{
  const original={messages:[{role:"system",content:"original-policy"},{role:"user",content:"task"},
    {role:"assistant",content:"tool-call"}]};
  const forwarded=wirePayload(original,"frozen-evidence");
  assert.equal(forwarded.messages.filter(m=>m.role==="system").length,1);
  assert.equal(forwarded.messages[0].role,"system");
  assert(forwarded.messages[0].content.includes("original-policy"));
  assert(forwarded.messages[0].content.includes("frozen-evidence"));
  assert.deepEqual(forwarded.messages.slice(1),original.messages.slice(1));
  assert.equal(original.messages[0].content,"original-policy");
});

