import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import ts from 'typescript';
import {ref} from 'vue';
const deferred = () => {let resolve, reject; const promise = new Promise((yes, no) => {resolve = yes; reject = no;}); return {promise, resolve, reject};};
for (const view of ['Conversations', 'Trips']) {
  let script = (await fs.readFile(new URL(`../src/views/${view}.vue`, import.meta.url), 'utf8')).match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1];
  const ast = ts.createSourceFile('Archive.ts', script, ts.ScriptTarget.Latest, true);
  for (const node of [...ast.statements].reverse()) if (ts.isImportDeclaration(node)) script = script.slice(0, node.pos) + script.slice(node.end);
  const compiled = ts.transpileModule(script + '\nreturn {load,items,page,total,loading,error};', {compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext}}).outputText;
  function setup() {
    const reads = []; let unmount = () => {};
    const read = page => {const pending = deferred(); reads.push({page, ...pending}); return pending.promise;};
    const env = {ref, onMounted: () => {}, onBeforeUnmount: fn => {unmount = fn;}, useRouter: () => ({}), listTrips: read, listConversations: read,
      deleteTrip: () => {}, deleteConversation: () => {}, showConfirmDialog: () => {}, showToast: () => {}, messageOf: error => error.message};
    return {ui: new Function(...Object.keys(env), compiled)(...Object.values(env)), reads, unmount: () => unmount()};
  }
  test(`${view}: late earlier page cannot replace latest page items`, async () => {
    const f = setup(); const first = f.ui.load(); f.ui.page.value = 2; const latest = f.ui.load();
    f.reads[1].resolve({items: [{id: 'page2'}], total: 20}); await latest;
    f.reads[0].resolve({items: [{id: 'page1'}], total: 11}); await first;
    assert.deepEqual(f.ui.items.value, [{id: 'page2'}]); assert.equal(f.ui.total.value, 20);
  });
  test(`${view}: old error cannot clear latest loading or error state`, async () => {
    const f = setup(); const first = f.ui.load(); f.ui.page.value = 2; const latest = f.ui.load();
    f.reads[0].reject(Error('old failure')); await first;
    assert.equal(f.ui.loading.value, true); assert.equal(f.ui.error.value, '');
    f.reads[1].resolve({items: [{id: 'page2'}], total: 20}); await latest;
    assert.equal(f.ui.loading.value, false);
  });
  test(`${view}: leaving discards late page completions`, async () => {
    const f = setup(); const first = f.ui.load(); f.unmount();
    f.reads[0].resolve({items: [{id: 'old page'}], total: 20}); await first;
    assert.deepEqual(f.ui.items.value, []); assert.equal(f.ui.total.value, 0);
  });
  test(`${view}: current page failure remains retryable`, async () => {
    const f = setup(); const first = f.ui.load(); f.reads[0].reject(Error('current failure')); await first;
    assert.equal(f.ui.error.value, 'current failure'); assert.equal(f.ui.loading.value, false);
    const retry = f.ui.load(); f.reads[1].resolve({items: [{id: 'retried'}], total: 1}); await retry;
    assert.equal(f.ui.error.value, ''); assert.deepEqual(f.ui.items.value, [{id: 'retried'}]);
  });
}
