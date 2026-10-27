import test from "node:test";
import assert from "node:assert/strict";
import {RequestPacer} from "../../scripts/agent-eval/pacer.mjs";
function clock(options={}){
  let time=0;
  const pacer=new RequestPacer({tokenBudget:100,windowMs:1000,minIntervalMs:100,
    now:()=>time,sleep:async ms=>{time+=ms;},...options});
  return {pacer,now:()=>time};
}
test("reservation prevents aggregate token bursts until the window expires",async()=>{
  const {pacer,now}=clock();
  const first=await pacer.acquire(70);first.complete(80);
  const second=await pacer.acquire(30);
  assert.equal(now(),1000);assert.equal(second.waitMs,1000);
});
test("actual usage releases excess reservation while minimum start interval remains enforced",async()=>{
  const {pacer,now}=clock();
  const first=await pacer.acquire(90);first.complete(10);
  const second=await pacer.acquire(80);
  assert.equal(now(),100);assert.equal(second.waitMs,100);
});
test("a 429 cooldown defers following requests without retrying the failed one",async()=>{
  const {pacer,now}=clock();
  await pacer.acquire(30);pacer.rateLimited();
  const next=await pacer.acquire(30);
  assert.equal(now(),65000);assert.equal(next.waitMs,65000);
});
test("an aborted queue entry does not block future work and oversized reservations are rejected",async()=>{
  const {pacer}=clock();
  const controller=new AbortController();controller.abort(new Error("cancelled"));
  await assert.rejects(pacer.acquire(20,controller.signal),/cancelled/);
  const next=await pacer.acquire(20);assert.equal(next.waitMs,0);
  await assert.rejects(pacer.acquire(101),/exceeds/);
});
