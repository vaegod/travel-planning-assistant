import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import ts from 'typescript';
import {ref,reactive,computed,nextTick} from 'vue';
import {mergeMessages} from '../src/utils/messageState.ts';
const deferred=()=>{let resolve;const promise=new Promise(yes=>resolve=yes);return{promise,resolve};};
const tick=()=>new Promise(resolve=>setImmediate(resolve));
test('history keeps the live reply identity, content and status until stream completion',()=>{
  const live=reactive({id:'temporary',seq:99,requestId:'active',role:'ai',content:'实时新内容',status:'streaming',statusText:'',timestamp:'now'});
  const row={id:'stored',seq:2,requestId:'active',role:'assistant',content:'旧快照',status:'completed',createdAt:'then'};
  const merged=mergeMessages([live],[row],'active');
  assert.equal(merged[0],live);assert.equal(merged[0].content,'实时新内容');
  assert.equal(merged[0].status,'streaming');assert.equal(merged[0].seq,2);
  live.content+='后续';assert.equal(merged[0].content,'实时新内容后续');
  const terminal=mergeMessages(merged,[{...row,content:'最终保存内容'}]);
  assert.equal(terminal[0].content,'最终保存内容');assert.equal(terminal[0].status,'completed');
  assert.equal(terminal[0].id,'stored');
});
test('protecting one live reply does not freeze unrelated history entries',()=>{
  const live={id:'live',seq:3,requestId:'active',role:'ai',content:'实时',status:'streaming'};
  const old={id:'old',seq:1,requestId:'old',role:'ai',content:'旧值',status:'error'};
  const merged=mergeMessages([old,live],[
    {id:'db-old',seq:1,requestId:'old',role:'assistant',content:'已保存',status:'completed',createdAt:'now'},
    {id:'db-live',seq:3,requestId:'active',role:'assistant',content:'旧快照',status:'streaming',createdAt:'now'}
  ],'active');
  assert.equal(merged[0].content,'已保存');assert.equal(merged[1],live);
});
const file=await fs.readFile(new URL('../src/views/Chat.vue',import.meta.url),'utf8');
let script=file.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1];
const ast=ts.createSourceFile('Chat.ts',script,ts.ScriptTarget.Latest,true);
for(const node of [...ast.statements].reverse())
  if(ts.isImportDeclaration(node))script=script.slice(0,node.pos)+script.slice(node.end);
const compiled=ts.transpileModule(script+'\nreturn {loadRoute,sendQuestion,messages,isStreaming,historyError};',
  {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText;
test('the previous turn reload cannot detach the next turn streaming callback',async()=>{
  const late=deferred(),handles=[];let reads=0;
  const env={ref,reactive,computed,nextTick,onMounted:()=>{},onBeforeUnmount:()=>{},watch:()=>{},showToast:()=>{},
    useRoute:()=>({params:{id:'chat'}}),useRouter:()=>({}),
    getConversation:async()=>({id:'chat',context:{},activeRequestId:null}),
    getMessages:async()=>++reads===2?late.promise:{items:[],hasMore:false,nextBefore:null},
    newConversation:()=>{},api:()=>{},messageOf:e=>String(e),mergeMessages,
    startChat:(id,text,onChunk,requestId)=>{
      const finish=deferred(),handle={requestId,finished:finish.promise,cancel:async()=>true};
      handles.push({requestId,finish,onChunk});return handle;
    }};
  const ui=new Function(...Object.keys(env),compiled)(...Object.values(env));
  await ui.loadRoute();await ui.sendQuestion('第一问');
  handles[0].onChunk('第一答');handles[0].finish.resolve({status:'done'});
  for(let i=0;i<10&&reads<2;i++)await tick();
  assert.equal(reads,2);assert.equal(ui.isStreaming.value,false);
  await ui.sendQuestion('第二问');const current=handles[1];current.onChunk('实时');
  late.resolve({items:[{id:'db-ai',seq:4,requestId:current.requestId,role:'assistant',content:'旧快照',status:'streaming',createdAt:'now'}],hasMore:false,nextBefore:1});
  await tick();await tick();current.onChunk('后续');
  assert.equal(ui.messages.value.find(m=>m.requestId===current.requestId&&m.role==='ai').content,'实时后续');
  assert.equal(ui.isStreaming.value,true);
  current.finish.resolve({status:'done'});await tick();
});
test('a prior reload arriving after the next turn completes cannot regress saved content or status',async()=>{
  const late=deferred(),handles=[];let reads=0;
  const row=(id,content,status)=>({id:'db-'+id,seq:4,requestId:id,role:'assistant',content,status,createdAt:'now'});
  const env={ref,reactive,computed,nextTick,onMounted:()=>{},onBeforeUnmount:()=>{},watch:()=>{},showToast:()=>{},
    useRoute:()=>({params:{id:'chat'}}),useRouter:()=>({}),
    getConversation:async()=>({id:'chat',context:{},activeRequestId:null}),
    getMessages:async()=>{
      reads++;
      if(reads===2)return late.promise;
      return{items:reads>=3?[row(handles[1].requestId,'完整保存内容','completed')]:[],hasMore:false,nextBefore:null};
    },
    newConversation:()=>{},api:()=>{},messageOf:e=>String(e),mergeMessages,
    startChat:(id,text,onChunk,requestId)=>{
      const finish=deferred();handles.push({requestId,finish,onChunk});
      return{requestId,finished:finish.promise,cancel:async()=>true};
    }};
  const ui=new Function(...Object.keys(env),compiled)(...Object.values(env));
  await ui.loadRoute();await ui.sendQuestion('第一问');
  handles[0].onChunk('第一答');handles[0].finish.resolve({status:'done'});
  for(let i=0;i<10&&reads<2;i++)await tick();
  await ui.sendQuestion('第二问');const second=handles[1];second.onChunk('完整保存内容');
  second.finish.resolve({status:'done'});
  for(let i=0;i<10&&reads<3;i++)await tick();
  await tick();
  late.resolve({items:[row(second.requestId,'旧片段','streaming')],hasMore:false,nextBefore:null});
  await tick();await tick();
  const displayed=ui.messages.value.find(m=>m.requestId===second.requestId&&m.role==='ai');
  assert.equal(displayed.content,'完整保存内容');assert.equal(displayed.status,'completed');
  assert.equal(ui.isStreaming.value,false);
});
