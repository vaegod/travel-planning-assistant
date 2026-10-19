import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import ts from 'typescript';
import {ref,reactive,computed,nextTick} from 'vue';
import {mergeMessages} from '../src/utils/messageState.ts';
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return{promise,resolve,reject};};
const tick=()=>new Promise(resolve=>setImmediate(resolve));
let script=(await fs.readFile(new URL('../src/views/Chat.vue',import.meta.url),'utf8')).match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1];
const ast=ts.createSourceFile('Chat.ts',script,ts.ScriptTarget.Latest,true);
for(const node of [...ast.statements].reverse())if(ts.isImportDeclaration(node))script=script.slice(0,node.pos)+script.slice(node.end);
const code=ts.transpileModule(script+'\nreturn {loadRoute,sendQuestion,stopGeneration,stopRemote,sendBlocked,stopping,pendingStop,isStreaming,conversationId};',
  {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText;
function setup() {
  const route={params:{id:'a'}},handles=[],cancelCalls=[],retryCalls=[],historyReads=[];
  const remote=new Map();const retries=[];
  let nextHistory=null,unmount;
  const env={ref,reactive,computed,nextTick,onMounted:()=>{},onBeforeUnmount:fn=>{unmount=fn;},watch:()=>{},
    window:{removeEventListener(){},addEventListener(){}},
    useRoute:()=>route,useRouter:()=>({}),showToast:()=>{},messageOf:e=>e.message,mergeMessages,
    getConversation:async id=>({id,context:{},activeRequestId:remote.get(id)||null}),
    getMessages:id=>{historyReads.push(id);if(nextHistory){const value=nextHistory;nextHistory=null;return value;}return Promise.resolve({items:[],hasMore:false,nextBefore:null});},
    newConversation:()=>{},
    api:(path)=>{retryCalls.push(path);return retries.shift()?.promise||Promise.resolve({cancelled:false});},
    startChat:(id,text,onChunk,requestId)=>{
      const finished=deferred(),ack=deferred();
      const handle={requestId,finished:finished.promise,cancel:()=>{cancelCalls.push(requestId);return ack.promise;}};
      handles.push({id,text,requestId,handle,finished,ack});remote.set(id,requestId);return handle;
    }};
  const ui=new Function(...Object.keys(env),code)(...Object.values(env));
  return{ui,route,handles,remote,cancelCalls,retryCalls,retries,historyReads,
    setHistory:promise=>{nextHistory=promise;},unmount:()=>unmount()};
}
test('stop locks send until both cancellation confirmation and history refresh complete',async()=>{
  const f=setup();await f.ui.loadRoute();await f.ui.sendQuestion('第一问');
  f.ui.stopGeneration();f.ui.stopGeneration();
  assert.equal(f.ui.stopping.value,true);assert.equal(f.ui.sendBlocked.value,true);
  await f.ui.sendQuestion('立即第二问');assert.equal(f.handles.length,1);assert.equal(f.cancelCalls.length,1);
  const history=deferred();f.setHistory(history.promise);f.remote.delete('a');f.handles[0].ack.resolve(true);
  await tick();assert.equal(f.ui.sendBlocked.value,true);
  history.resolve({items:[],hasMore:false,nextBefore:null});await tick();
  assert.equal(f.ui.stopping.value,false);assert.equal(f.ui.sendBlocked.value,false);
  await f.ui.sendQuestion('确认后的第二问');assert.equal(f.handles.length,2);
});
test('false confirmation stays locked and retry uses a fresh existing DELETE',async()=>{
  const f=setup();await f.ui.loadRoute();await f.ui.sendQuestion('第一问');f.ui.stopGeneration();
  f.handles[0].ack.resolve(false);await tick();
  assert.equal(f.ui.stopping.value,false);assert.equal(f.ui.sendBlocked.value,true);
  assert.match(f.ui.pendingStop.value.error,/未能确认/);
  await f.ui.sendQuestion('不应发出');assert.equal(f.handles.length,1);
  const retry=deferred();f.retries.push(retry);const work=f.ui.stopRemote();
  assert.equal(f.ui.stopping.value,true);f.remote.delete('a');retry.resolve({cancelled:true});await work;
  assert.equal(f.retryCalls.length,1);assert.equal(f.ui.sendBlocked.value,false);
});
test('confirmation alone does not unlock a still-busy conversation',async()=>{
  const f=setup();await f.ui.loadRoute();await f.ui.sendQuestion('第一问');f.ui.stopGeneration();
  f.handles[0].ack.resolve(true);await tick();
  assert.equal(f.ui.stopping.value,false);assert.equal(f.ui.sendBlocked.value,true);
  assert.match(f.ui.pendingStop.value.error,/尚未确认/);
});
test('history failure after confirmation keeps retry available instead of unlocking',async()=>{
  const f=setup();await f.ui.loadRoute();await f.ui.sendQuestion('第一问');f.ui.stopGeneration();
  const history=deferred();f.setHistory(history.promise);f.remote.delete('a');f.handles[0].ack.resolve(true);
  await tick();history.reject(Error('history unavailable'));await tick();
  assert.equal(f.ui.stopping.value,false);assert.equal(f.ui.sendBlocked.value,true);
  const retry=deferred();f.retries.push(retry);const work=f.ui.stopRemote();retry.resolve({cancelled:true});await work;
  assert.equal(f.ui.sendBlocked.value,false);
});
test('network rejection leaves a locked state with a retry message',async()=>{
  const f=setup();await f.ui.loadRoute();await f.ui.sendQuestion('第一问');f.ui.stopGeneration();
  f.handles[0].ack.reject(Error('network down'));await tick();
  assert.equal(f.ui.stopping.value,false);assert.equal(f.ui.sendBlocked.value,true);
  assert.equal(f.ui.pendingStop.value.error,'network down');
});
test('old conversation cancellation cannot unlock another conversation stopping state',async()=>{
  const f=setup();await f.ui.loadRoute();await f.ui.sendQuestion('A');f.ui.stopGeneration();
  f.route.params={id:'b'};await f.ui.loadRoute();assert.equal(f.ui.sendBlocked.value,false);
  await f.ui.sendQuestion('B');f.ui.stopGeneration();const target=f.ui.pendingStop.value;
  f.remote.delete('a');f.handles[0].ack.resolve(true);await tick();
  assert.equal(f.ui.pendingStop.value,target);assert.equal(f.ui.stopping.value,true);
  f.remote.delete('b');f.handles[1].ack.resolve(true);await tick();
  assert.equal(f.ui.sendBlocked.value,false);
});
test('leaving during confirmation discards late UI work without starting another refresh',async()=>{
  const f=setup();await f.ui.loadRoute();await f.ui.sendQuestion('A');f.ui.stopGeneration();
  const reads=f.historyReads.length;f.unmount();f.remote.delete('a');f.handles[0].ack.resolve(true);await tick();
  assert.equal(f.historyReads.length,reads);assert.equal(f.cancelCalls.length,1);
});
test('a remote active request uses the same lock and duplicate clicks do not duplicate DELETE',async()=>{
  const f=setup();f.remote.set('a','remote-request');await f.ui.loadRoute();
  const ack=deferred();f.retries.push(ack);
  const work=f.ui.stopRemote();await f.ui.stopRemote();
  assert.equal(f.retryCalls.length,1);assert.equal(f.ui.sendBlocked.value,true);
  f.remote.delete('a');ack.resolve({cancelled:true});await work;
  assert.equal(f.ui.sendBlocked.value,false);
});
