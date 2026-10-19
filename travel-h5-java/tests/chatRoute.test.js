import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import ts from 'typescript';

const file=await fs.readFile(new URL('../src/views/Chat.vue',import.meta.url),'utf8');
const script=file.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1];
const ast=ts.createSourceFile('Chat.ts',script,ts.ScriptTarget.Latest,true);
let code=script;
for(const node of [...ast.statements].reverse())
  if(ts.isImportDeclaration(node))code=code.slice(0,node.pos)+code.slice(node.end);
const compiled=ts.transpileModule(code+'\nreturn {loadRoute,loadOlder,loadingHistory,loadingOlder,conversationId,conversation,messages,historyError};',
  {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText;
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return{promise,resolve,reject};};
function setup() {
  const reads=[],route={params:{id:'a'}};
  const env={
    ref:value=>({value}),reactive:v=>v,computed:fn=>({get value(){return fn();}}),
    nextTick:async()=>{},onMounted:()=>{},onBeforeUnmount:()=>{},watch:()=>{},
    useRouter:()=>({}),useRoute:()=>route,showToast:()=>{},startChat:()=>{},
    getConversation:id=>{const pending=deferred();reads.push({id,pending});return pending.promise;},
    getMessages:async()=>({items:[],nextBefore:null,hasMore:false}),
    newConversation:()=>{},api:()=>{},messageOf:e=>String(e),mergeMessages:(a,b)=>b
  };
  const ui=new Function(...Object.keys(env),compiled)(...Object.values(env));
  return{ui,route,reads};
}
test('leaving a loading saved chat enables the new chat even after the old request completes',async()=>{
  const {ui,route,reads}=setup();
  const pending=ui.loadRoute();assert.equal(ui.loadingHistory.value,true);
  route.params={};await ui.loadRoute();assert.equal(ui.loadingHistory.value,false);
  reads[0].pending.resolve({id:'a',activeRequestId:null});await pending;
  assert.equal(ui.conversationId.value,null);assert.equal(ui.loadingHistory.value,false);
  assert.equal(ui.conversation.value,null);
});
test('returning to the same conversation ignores the earlier response and loading state',async()=>{
  const {ui,route,reads}=setup();
  const first=ui.loadRoute();route.params={};await ui.loadRoute();
  route.params={id:'a'};const last=ui.loadRoute();
  reads[1].pending.resolve({id:'a',title:'new response'});await last;
  reads[0].pending.resolve({id:'a',title:'stale response'});await first;
  assert.equal(ui.conversation.value.title,'new response');
  assert.equal(ui.loadingHistory.value,false);
});
test('late history failure cannot poison the new chat error state',async()=>{
  const {ui,route,reads}=setup();
  const first=ui.loadRoute();route.params={};await ui.loadRoute();
  reads[0].pending.reject(Error('old failure'));await first;
  assert.equal(ui.loadingHistory.value,false);assert.equal(ui.historyError.value,'');
});
