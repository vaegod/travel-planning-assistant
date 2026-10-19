// Opt-in: writes only newly registered QA accounts; at most six real model calls.
import assert from 'node:assert/strict';
import {mkdir, writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {localClient, testAccount} from './local-api.mjs';
import {readChatResponse} from '../travel-h5-java/src/utils/chatStream.ts';

assert(process.argv.includes('--live'), 'Explicit --live required: uses real model credits');
const client = localClient('http://127.0.0.1:3200');
const account = testAccount();
const report = {at: new Date().toISOString(), passed: false, maxModelCalls: 6,
  actualModelRequests: 0, checks: {}, turns: []};
const output = '.runtime/autobug-round1-live.json';
const compact = text => text.replace(/[\s,，]/g, '');
const digest = text => createHash('sha256').update(text).digest('hex');
await mkdir('.runtime', {recursive: true});
async function save() {await writeFile(output, JSON.stringify(report, null, 2) + '\n');}
async function turn(id, message, patterns) {
  assert(report.actualModelRequests < report.maxModelCalls);
  report.actualModelRequests++;
  const value = await client.chat(id, message);
  const messages = (await client.api(`/api/conversations/${id}/messages`)).items;
  const saved = messages.find(item => item.requestId === value.requestId && item.role === 'assistant');
  assert(saved && saved.status === 'completed');
  assert.equal(saved.content, value.answer);
  assert(!saved.evidence);
  assert.equal((await client.api(`/api/conversations/${id}`)).activeRequestId, null);
  const missed = patterns.filter(pattern => !pattern.test(compact(value.answer))).map(String);
  report.turns.push({requestId: value.requestId, question: message,
    chars: value.answer.length, durationMs: value.durationMs,
    answerSha256: digest(value.answer), semanticPassed: missed.length === 0,
    missed, excerpt: value.answer.slice(0, 180)});
  await save();
  assert.equal(missed.length, 0, 'Semantic check needs manual review; see isolated test excerpt');
  console.log(JSON.stringify({check: 'chat', requestId: value.requestId, passed: true}));
}
try {
  await writeFile('.runtime/autobug-round1-account.json', JSON.stringify(account) + '\n');
  await client.register(account);
  const conversation = await client.api('/api/conversations', 'POST', {});
  report.conversationId = conversation.id;
  await turn(conversation.id,
    '这是新的旅行讨论：武汉3天，总预算1800元，不吃辣。请记住这些条件，60字以内确认。',
    [/武汉/, /3天|三天/, /1800|一千八百/, /不吃辣|不辣|无辣|清淡/]);
  await turn(conversation.id,
    '目的地改为南京，天数改为2天，预算与饮食偏好保持刚才的条件。请在60字以内复述当前全部条件。',
    [/南京/, /2天|两天|二天/, /1800|一千八百/, /不吃辣|不辣|无辣|清淡/]);
  await client.login(account);
  await turn(conversation.id,
    '重新登录后，请只复述这次讨论最终的城市、天数、总预算和饮食偏好，60字以内。',
    [/南京/, /2天|两天|二天/, /1800|一千八百/, /不吃辣|不辣|无辣|清淡/]);
  assert.deepEqual((await client.api(`/api/conversations/${conversation.id}`)).context, conversation.context);
  report.checks.multiturnReloginAndReferenceUnchanged = true;
  const pages = []; let before = null;
  for (let n = 0; n < 4; n++) {
    const page = await client.api(`/api/conversations/${conversation.id}/messages?pageSize=2${before ? '&before=' + before : ''}`);
    pages.unshift(...page.items);
    if (!page.hasMore) break;
    before = page.nextBefore;
  }
  assert.equal(pages.length, 6);
  assert.equal(new Set(pages.map(item => item.id)).size, 6);
  assert(pages.every((item, index) => !index || item.seq > pages[index - 1].seq));
  report.checks.historyPagination = true;

  const cancelConversation = await client.api('/api/conversations', 'POST', {});
  const requestId = crypto.randomUUID();
  report.actualModelRequests++;
  let cancelWork, content = '';
  const response = await client.request('/api/travel/chat', 'POST', {
    conversationId: cancelConversation.id, requestId,
    message: '请写一份西安四天旅游建议，涵盖每天三个时段和饮食，不少于600字。',
  }, 150000);
  await readChatResponse(response, requestId, chunk => {
    content += chunk;
    cancelWork ??= client.api(`/api/travel/chat/${requestId}`, 'DELETE');
  });
  assert(cancelWork, 'Must receive content before cancellation');
  assert.equal((await cancelWork).cancelled, true);
  assert.equal((await client.api(`/api/conversations/${cancelConversation.id}`)).activeRequestId, null);
  const stopped = (await client.api(`/api/conversations/${cancelConversation.id}/messages`)).items;
  assert.equal(stopped.length, 2);
  assert.equal(stopped[1].status, 'stopped');
  report.turns.push({requestId, stopped: true, partialChars: content.length});
  await turn(cancelConversation.id,
    '现在是新问题：只回复“收到”，不要续写之前的旅行建议。', [/收到/]);
  report.checks.cancelAcknowledgedThenResend = true;

  assert(report.actualModelRequests < report.maxModelCalls);
  report.actualModelRequests++;
  const started = performance.now();
  const generated = await client.request('/api/travel/recommend', 'POST', {
    city: '兰州', days: 2, budget: 1200, requestId: crypto.randomUUID(),
  }, 130000);
  assert.equal(generated.status, 200);
  const envelope = await generated.json();
  assert(envelope.success && envelope.data.success);
  const plan = envelope.data;
  assert.equal(plan.city, '兰州'); assert.equal(plan.days, 2);
  assert.equal(plan.totalBudget, 1200); assert.equal(plan.dailyItinerary.length, 2);
  assert(!plan.evidence);
  for (const day of plan.dailyItinerary)
    for (const slot of [day.morning, day.afternoon, day.evening])
      assert(slot.spot && slot.description && !slot.placeId && !slot.route);
  const trip = await client.api('/api/trips', 'POST', {title: '自动回归独立测试行程', plan});
  report.tripId = trip.trip.id;
  await client.login(account);
  assert.deepEqual(await client.api(`/api/trips/${trip.trip.id}`), trip);
  const linked = await client.api('/api/conversations', 'POST', {tripId: trip.trip.id});
  assert.equal(linked.tripId, trip.trip.id);
  report.checks.directPlanSaveReloginAndLink = {passed: true, durationMs: Math.round(performance.now() - started)};
  const other = localClient('http://127.0.0.1:3200');
  await other.register(testAccount());
  for (const path of [`/api/trips/${trip.trip.id}`, `/api/conversations/${conversation.id}`,
    `/api/conversations/${conversation.id}/messages`])
    assert.equal((await other.reply(path)).status, 404);
  assert.equal((await client.reply('/api/knowledge/documents')).status, 404);
  assert.equal((await client.reply('/api/planning/jobs', 'POST', {city: '兰州', days: 2, budget: 1200})).status, 410);
  report.checks.accountIsolationAndExtensionsDisabled = true;
  report.passed = true;
} catch (error) {
  report.error = error.message;
  process.exitCode = 1;
} finally {
  await save();
  console.log(JSON.stringify({passed: report.passed, modelRequests: report.actualModelRequests,
    checks: report.checks, error: report.error, evidence: output}));
}
