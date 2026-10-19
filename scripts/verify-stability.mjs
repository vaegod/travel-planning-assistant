// Opt-in real-model smoke test. Creates a separate local account; never edits wl.
// Run from the project root: node scripts/verify-stability.mjs
import assert from 'node:assert/strict';
import {mkdir, writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {localClient, testAccount} from './local-api.mjs';
const client = localClient('http://127.0.0.1:3200'), account = testAccount();
const days = Number(process.env.STABILITY_DAYS || 10);
assert(Number.isInteger(days) && days >= 1 && days <= 30);
const report = {at: new Date().toISOString(), scope: 'native-3200', checks: {}, turns: []};
await mkdir('.runtime', {recursive:true});
try {
  await client.register(account);
  await writeFile('.runtime/stability-account.json', JSON.stringify(account));
  assert.equal((await client.reply('/api/knowledge/documents')).status, 404);
  report.checks.knowledgeDisabled = true;
  const request = {city:'浙江省杭州市', days, budget:5000};
  const started = performance.now();
  const response = await client.request('/api/travel/recommend', 'POST', request, 150000);
  assert.equal(response.status, 200, 'Real itinerary generation failed');
  const {data:plan, success} = await response.json();
  assert(success && plan.success);
  assert.equal(plan.city, request.city);
  assert.equal(plan.days, days);
  assert.equal(plan.totalBudget, 5000);
  assert.equal(plan.dailyItinerary.length, days);
  assert(!plan.evidence);
  plan.dailyItinerary.forEach((day,i)=>{
    assert.equal(day.day,i+1);
    for(const time of ['morning','afternoon','evening']) assert(day[time].spot && !day[time].sourceUrl);
  });
  report.plan = {days, city:plan.city, durationMs:Math.round(performance.now()-started),
    bytes:JSON.stringify(plan).length};
  const first = await client.api('/api/trips','POST',{title:'稳定性验收', plan});
  const revised = structuredClone(plan);
  revised.tips = ['版本2验收标记', ...(revised.tips||[])];
  const second = await client.api('/api/trips/'+first.trip.id,'PUT',{baseVersion:1, plan:revised});
  assert.equal(second.trip.currentVersion,2);
  assert.deepEqual((await client.api('/api/trips/'+first.trip.id+'?version=1')).plan, first.plan);
  report.tripId = first.trip.id;
  report.checks.planAndVersionPersistence = true;
  const conversation = await client.api('/api/conversations','POST',{tripId:first.trip.id});
  const questions = [
    ['预算50元在杭州能吃什么？请在60字以内回答。',5000],
    ['酒店预算300元一晚，有什么推荐？请在60字以内回答。',5000],
    ['把总预算改为6000元。请按新预算给建议，用一句话说出现在讨论的总预算和目的地。',5000],
    ['那之前说的酒店单晚预算是多少？请用一句话回答。',5000],
  ];
  for(const [question,budget] of questions) {
    const turn = await client.chat(conversation.id,question);
    assert(turn.chunks>0 && turn.answer.length>0);
    const state = (await client.api('/api/conversations/'+conversation.id)).context;
    assert.equal(state.budget,budget);
    assert.equal(state.days,days);
    report.turns.push({requestId:turn.requestId, chars:turn.answer.length,
      chunks:turn.chunks, durationMs:turn.durationMs, firstContentMs:turn.firstContentMs,
      persistedBudget:state.budget, answerSha256:createHash('sha256').update(turn.answer).digest('hex')});
    if(report.turns.length===3) {
      assert.match(turn.answer,/6000|6,000|六千/);
      report.checks.modelUnderstandsNewBudgetFromMessages = true;
    }
    if(report.turns.length===4) {
      assert.match(turn.answer,/300|三百/,'The final answer must recall the hotel budget');
      report.checks.multiTurnHotelBudgetRecall = true;
    }
  }
  report.checks.subBudgetsDoNotChangeTripBudget = true;
  report.checks.chatTextDoesNotRewriteReference = true;
  const history = await client.api('/api/conversations/'+conversation.id+'/messages');
  assert.equal(history.items.length,8);
  assert(history.items.every(m=>m.status==='completed' && !m.evidence));
  assert.equal((await client.api('/api/trips/'+first.trip.id)).plan.totalBudget,5000);
  report.checks.chatDoesNotOverwriteSavedTrip = true;
  // Export only this test account's local browser cookie, to an ignored file.
  const login = await client.request('/api/auth/login','POST',account);
  assert.equal(login.status,200);
  const session = login.headers.getSetCookie().find(s=>s.startsWith('JSESSIONID='));
  assert(session);
  await writeFile('.runtime/stability-browser-state.json',JSON.stringify({cookies:[{
    name:'JSESSIONID',value:session.split(';')[0].slice('JSESSIONID='.length),
    domain:'127.0.0.1',path:'/',expires:-1,httpOnly:true,secure:false,sameSite:'Lax'
  }],origins:[]}));
  report.passed = true;
} catch(error) {
  report.passed = false; report.error = error.message; process.exitCode = 1;
} finally {
  await writeFile('.runtime/stability-live.json',JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report));
}
