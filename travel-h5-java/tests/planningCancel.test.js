import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import ts from 'typescript';
import {createTripVersionLoader} from '../src/utils/tripVersionLoader.ts';
import {validateTrip} from '../src/utils/validation.js';
const file=await fs.readFile(new URL('../src/views/Detail.vue',import.meta.url),'utf8');
const script=file.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1];
const ast=ts.createSourceFile('Detail.ts',script,ts.ScriptTarget.Latest,true);
let code=script;
for(const node of [...ast.statements].reverse())
  if(ts.isImportDeclaration(node))code=code.slice(0,node.pos)+code.slice(node.end);
const compiled=ts.transpileModule(code+'\nreturn {fetchTripData,tripData,errorMsg,isLoading,saveCurrent,goToChat};',
  {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText;
function setup({saveTrip=()=>{},newConversation=()=>{}}={}) {
  const calls=[],cancels=[],navigation=[],toasts=[];let unmount;
  const env={
    ref:value=>({value}),reactive:v=>v,onMounted:()=>{},onBeforeUnmount:fn=>{unmount=fn;},
    useRouter:()=>({back(){},replace:async value=>navigation.push(value),push:async value=>navigation.push(value)}),
    useRoute:()=>({query:{city:'杭州',days:'2',budget:'5000'}}),
    showToast:value=>toasts.push(value),getTrip:()=>{},saveTrip,newConversation,
    createTripVersionLoader,validateTrip,messageOf:e=>String(e),
    recommendTrip:(city,days,budget,options)=>new Promise((resolve,reject)=>{
      calls.push({options,resolve});options.signal.addEventListener('abort',()=>reject(Error('cancelled')),{once:true});
    }),
    cancelRecommendedTrip:async id=>{cancels.push(id);return{cancelled:true};}
  };
  const ui=new Function(...Object.keys(env),compiled)(...Object.values(env));
  return{ui,calls,cancels,navigation,toasts,unmount:()=>unmount()};
}
test('leaving an active planning page aborts HTTP and cancels the exact server request',async()=>{
  const {ui,calls,cancels,unmount}=setup();
  const pending=ui.fetchTripData();
  assert.equal(calls.length,1);assert.equal(calls[0].options.signal.aborted,false);
  unmount();await pending;
  assert.equal(calls[0].options.signal.aborted,true);
  assert.deepEqual(cancels,[calls[0].options.requestId]);
  assert.equal(ui.tripData.value,null);assert.equal(ui.errorMsg.value,'');
});
test('restarting planning cancels the previous request and leaves a completed result intact',async()=>{
  const {ui,calls,cancels,unmount}=setup();
  const old=ui.fetchTripData(),latest=ui.fetchTripData();
  assert.equal(calls[0].options.signal.aborted,true);
  const plan={city:'杭州',days:2,totalBudget:5000};
  calls[1].resolve(plan);await Promise.all([old,latest]);
  assert.deepEqual(ui.tripData.value,plan);assert.equal(ui.isLoading.value,false);
  assert.deepEqual(cancels,[calls[0].options.requestId]);
  unmount();assert.equal(cancels.length,1);
});

const deferred=()=>{let resolve;const promise=new Promise(yes=>resolve=yes);return{promise,resolve};};
async function showPlan(fixture) {
  const work=fixture.ui.fetchTripData();
  fixture.calls[0].resolve({success:true,city:'杭州',days:2,totalBudget:5000});
  await work;
}
test('a save that finishes after leaving retains its result without navigation or toast',async()=>{
  const save=deferred(),result={trip:{id:'saved-trip'}};let writes=0;
  const f=setup({saveTrip:()=>{writes++;return save.promise;}});
  await showPlan(f);const work=f.ui.saveCurrent();f.unmount();save.resolve(result);
  assert.deepEqual(await work,result);
  assert.equal(writes,1);assert.deepEqual(f.navigation,[]);assert.deepEqual(f.toasts,[]);
});
test('leaving while save-and-consult waits for save does not create an unwanted conversation',async()=>{
  const save=deferred();let conversations=0;
  const f=setup({saveTrip:()=>save.promise,newConversation:()=>{conversations++;}});
  await showPlan(f);const work=f.ui.goToChat();f.unmount();save.resolve({trip:{id:'saved-trip'}});
  await work;assert.equal(conversations,0);assert.deepEqual(f.navigation,[]);
});
test('leaving while conversation creation is pending prevents late chat navigation',async()=>{
  const created=deferred();
  const f=setup({saveTrip:async()=>({trip:{id:'saved-trip'}}),newConversation:()=>created.promise});
  await showPlan(f);await f.ui.saveCurrent();f.navigation.length=0;f.toasts.length=0;
  const work=f.ui.goToChat();f.unmount();created.resolve({id:'late-conversation'});
  await work;assert.deepEqual(f.navigation,[]);assert.deepEqual(f.toasts,[]);
});
test('save-and-consult still navigates while the page remains active',async()=>{
  let writes=0,conversations=0;
  const f=setup({saveTrip:async()=>{writes++;return{trip:{id:'saved-trip'}};},
    newConversation:async()=>{conversations++;return{id:'new-chat'};}});
  await showPlan(f);await f.ui.goToChat();
  assert.equal(writes,1);assert.equal(conversations,1);
  assert.deepEqual(f.navigation,[{path:'/detail',query:{tripId:'saved-trip'}},'/chat/new-chat']);
});
