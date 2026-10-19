import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import ts from 'typescript';
import {ref, reactive, computed, nextTick} from 'vue';
import {mergeMessages} from '../src/utils/messageState.ts';
const deferred = () => {let resolve; const promise = new Promise(yes => {resolve = yes;}); return {promise, resolve};};
const tick = () => new Promise(resolve => setImmediate(resolve));
let script = (await fs.readFile(new URL('../src/views/Chat.vue', import.meta.url), 'utf8')).match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1];
const ast = ts.createSourceFile('Chat.ts', script, ts.ScriptTarget.Latest, true);
for (const node of [...ast.statements].reverse()) if (ts.isImportDeclaration(node)) script = script.slice(0, node.pos) + script.slice(node.end);
const compiled = ts.transpileModule(script + '\nreturn {loadRoute,loadOlder,sendQuestion,messages,hasMore,nextBefore,isStreaming};', {compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext}}).outputText;
const row = (seq, requestId = 'old-' + Math.floor((seq - 1) / 2), content = '消息' + seq) => ({id: 'db-' + seq, seq, requestId, role: seq % 2 ? 'user' : 'assistant', content, status: 'completed', createdAt: 'now'});
const page = (first, last, hasMore) => ({items: Array.from({length: last - first + 1}, (_, i) => row(first + i)), hasMore, nextBefore: hasMore ? first : null});
function setup() {
  const route = {params: {id: 'a'}}, reads = [], handles = [], pages = [];
  const env = {ref, reactive, computed, nextTick, onMounted: () => {}, onBeforeUnmount: () => {}, watch: () => {}, showToast: () => {},
    useRoute: () => route, useRouter: () => ({}), newConversation: () => {}, api: () => {}, messageOf: e => e.message, mergeMessages,
    getConversation: async id => ({id, context: {}, activeRequestId: null}),
    getMessages: (id, before) => {reads.push({id, before}); return Promise.resolve(pages.shift());},
    startChat: (id, text, onChunk, requestId) => {const finished = deferred(); handles.push({onChunk, requestId, finished}); return {requestId, finished: finished.promise, cancel: async () => true};}};
  return {ui: new Function(...Object.keys(env), compiled)(...Object.values(env)), reads, handles, pages, route};
}
async function finish(f) {
  const handle = f.handles.at(-1), latest = page(23, 42, true);
  latest.items[18] = row(41, handle.requestId, '新问题'); latest.items[19] = row(42, handle.requestId, '新回答');
  f.pages.push(latest); handle.finished.resolve({status: 'done'});
  for (let i = 0; i < 5; i++) await tick();
}

test('exhausted history stays exhausted after a new turn refresh instead of repeating older pages', async () => {
  const f = setup(); f.pages.push(page(21, 40, true)); await f.ui.loadRoute();
  f.pages.push(page(1, 20, false)); await f.ui.loadOlder();
  assert.equal(f.ui.hasMore.value, false); assert.equal(f.ui.nextBefore.value, null);
  await f.ui.sendQuestion('新问题'); f.handles[0].onChunk('新回答'); await finish(f);
  assert.equal(f.ui.hasMore.value, false); assert.equal(f.ui.nextBefore.value, null);
  const reads = f.reads.length; await f.ui.loadOlder(); assert.equal(f.reads.length, reads);
  assert.equal(f.ui.messages.value.length, 42);
});

test('an older page completing during streaming keeps live chunks and exhausted cursor through completion', async () => {
  const f = setup(); f.pages.push(page(21, 40, true)); await f.ui.loadRoute();
  const older = deferred(); f.pages.push(older.promise); const work = f.ui.loadOlder();
  await f.ui.sendQuestion('新问题'); f.handles[0].onChunk('新');
  const live = f.ui.messages.value.at(-1); older.resolve(page(1, 20, false)); await work;
  f.handles[0].onChunk('回答'); assert.equal(f.ui.messages.value.at(-1), live); assert.equal(live.content, '新回答');
  await finish(f);
  assert.equal(f.ui.messages.value.at(-1).content, '新回答');
  assert.equal(f.ui.hasMore.value, false); assert.equal(f.ui.nextBefore.value, null);
});

test('partial history refresh preserves the older cursor that has already advanced', async () => {
  const f = setup(); f.pages.push(page(21, 40, true)); await f.ui.loadRoute();
  f.pages.push(page(11, 20, true)); await f.ui.loadOlder();
  await f.ui.sendQuestion('新问题'); f.handles[0].onChunk('新回答'); await finish(f);
  assert.equal(f.ui.hasMore.value, true); assert.equal(f.ui.nextBefore.value, 11);
});

test('switching conversations initializes the destination history independently after exhaustion', async () => {
  const f = setup(); f.pages.push(page(1, 2, false)); await f.ui.loadRoute();
  f.route.params = {id: 'b'}; f.pages.push(page(21, 40, true)); await f.ui.loadRoute();
  assert.equal(f.ui.hasMore.value, true); assert.equal(f.ui.nextBefore.value, 21);
  assert.equal(f.ui.messages.value.length, 20);
});
