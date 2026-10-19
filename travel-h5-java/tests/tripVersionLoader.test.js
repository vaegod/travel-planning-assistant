import test from 'node:test';
import assert from 'node:assert/strict';
import {createTripVersionLoader} from '../src/utils/tripVersionLoader.ts';
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};

test('late version 1 cannot replace the displayed plan or URL for version 2',async()=>{
  const one=deferred(),two=deferred(),displayed=[],urls=[],loading=[];
  const loader=createTripVersionLoader((id,v)=>v===1?one.promise:two.promise,
    (plan,id,v)=>{displayed.push(plan.version);urls.push(`${id}?version=${v}`);},assert.fail,v=>loading.push(v));
  const first=loader.load('trip',1),second=loader.load('trip',2);
  two.resolve({version:2});await second;one.resolve({version:1});await first;
  assert.deepEqual(displayed,[2]);assert.deepEqual(urls,['trip?version=2']);assert.deepEqual(loading,[true,true,false]);
});

test('an old error does not clear the loading state or show an error for the new version',async()=>{
  const one=deferred(),two=deferred(),errors=[],loading=[],seen=[];
  const loader=createTripVersionLoader((id,v)=>v===1?one.promise:two.promise,v=>seen.push(v),e=>errors.push(e),v=>loading.push(v));
  const first=loader.load('trip',1),second=loader.load('trip',2);
  one.reject(Error('old failure'));await first;
  assert.deepEqual(errors,[]);assert.deepEqual(loading,[true,true]);
  two.resolve({version:2});await second;assert.deepEqual(seen,[{version:2}]);assert.equal(loading.at(-1),false);
});

test('leaving or reloading a detail page discards stale completions',async()=>{
  const pending=deferred(),seen=[],errors=[],loading=[];
  const loader=createTripVersionLoader(()=>pending.promise,v=>seen.push(v),e=>errors.push(e),v=>loading.push(v));
  const work=loader.load('trip',1);loader.stop();pending.resolve({version:1});await work;
  assert.deepEqual(seen,[]);assert.deepEqual(errors,[]);assert.deepEqual(loading,[true]);
  await loader.load('trip',2);assert.deepEqual(loading,[true]);
});

test('a new page load invalidates a version read and the latest failure remains visible',async()=>{
  const pending=deferred(),seen=[],errors=[];
  let calls=0;
  const loader=createTripVersionLoader(()=>++calls===1?pending.promise:Promise.reject(Error('latest failure')),v=>seen.push(v),e=>errors.push(e.message),()=>{});
  const work=loader.load('trip',1);loader.invalidate();pending.resolve({version:1});await work;
  await loader.load('trip',2);assert.deepEqual(seen,[]);assert.deepEqual(errors,['latest failure']);
});
