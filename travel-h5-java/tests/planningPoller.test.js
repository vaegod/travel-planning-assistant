import test from "node:test";
import assert from "node:assert/strict";
import { createPlanningPoller } from "../src/utils/planningPoller.ts";

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
};

test("polls a running job until ready, then stops", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const states = ["RUNNING", "READY"],
    seen = [];
  const poller = createPlanningPoller(
    async () => ({ status: states.shift() }),
    (x) => seen.push(x.status),
    assert.fail,
  );
  await poller.refresh();
  assert.deepEqual(seen, ["RUNNING"]);
  t.mock.timers.tick(1000);
  await Promise.resolve();
  assert.deepEqual(seen, ["RUNNING", "READY"]);
  t.mock.timers.tick(10000);
  assert.equal(states.length, 0);
  poller.stop();
});

test("leaving the page discards an in-flight completion", async () => {
  const request = deferred(),
    seen = [];
  const poller = createPlanningPoller(
    () => request.promise,
    (x) => seen.push(x),
    assert.fail,
  );
  const pending = poller.refresh();
  poller.stop();
  request.resolve({ status: "READY" });
  await pending;
  assert.deepEqual(seen, []);
});

test("a late RUNNING response cannot replace cancellation or start polling again", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const stale = deferred(),
    seen = [];
  let calls = 0;
  const poller = createPlanningPoller(
    () =>
      ++calls === 1 ? stale.promise : Promise.resolve({ status: "CANCELLED" }),
    (x) => seen.push(x.status),
    assert.fail,
  );
  const first = poller.refresh();
  await poller.refresh();
  stale.resolve({ status: "RUNNING" });
  await first;
  t.mock.timers.tick(10000);
  assert.deepEqual(seen, ["CANCELLED"]);
  assert.equal(calls, 2);
  poller.stop();
});

test("network failure pauses polling, explicit refresh recovers", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  let calls = 0;
  const failures = [],
    seen = [];
  const poller = createPlanningPoller(
    async () => {
      if (++calls === 1) throw Error("offline");
      return { status: "READY" };
    },
    (x) => seen.push(x.status),
    (e) => failures.push(e.message),
  );
  await poller.refresh();
  t.mock.timers.tick(5000);
  assert.equal(calls, 1);
  assert.deepEqual(failures, ["offline"]);
  await poller.refresh();
  assert.deepEqual(seen, ["READY"]);
  poller.stop();
});

test("leaving the page suppresses a late failed request", async () => {
  const request = deferred(),
    failures = [];
  const poller = createPlanningPoller(
    () => request.promise,
    assert.fail,
    (e) => failures.push(e),
  );
  const pending = poller.refresh();
  poller.stop();
  request.reject(Error("late error"));
  await pending;
  assert.deepEqual(failures, []);
});
