import test, { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { auth, ensureAuth, clearAuth } from "../src/utils/auth.ts";
const originalFetch = globalThis.fetch;
const response = (user, status = 200) =>
  new Response(
    JSON.stringify(
      status === 200
        ? { success: true, data: user }
        : {
            success: false,
            errorCode: "HTTP_ERROR",
            message: "temporary failure",
          },
    ),
    { status, headers: { "Content-Type": "application/json" } },
  );
beforeEach(() => {
  clearAuth();
  auth.ready = false;
});
afterEach(() => {
  globalThis.fetch = originalFetch;
  clearAuth();
});
test("a temporary auth lookup failure can be retried without reloading the page", async () => {
  let calls = 0;
  globalThis.fetch = async () =>
    ++calls === 1
      ? response(null, 503)
      : response({ id: 7, username: "sample" });
  await assert.rejects(ensureAuth());
  assert.equal(auth.ready, false);
  await ensureAuth();
  assert.equal(calls, 2);
  assert.equal(auth.user.username, "sample");
  assert.equal(auth.ready, true);
});
test("late identity lookup cannot restore a user after logout or session expiry", async () => {
  let complete;
  globalThis.fetch = () =>
    new Promise((resolve) => {
      complete = resolve;
    });
  const pending = ensureAuth();
  clearAuth();
  complete(response({ id: 7, username: "old_user" }));
  await pending;
  assert.equal(auth.user, null);
  assert.equal(auth.ready, true);
});
test("simultaneous auth lookups share one pending HTTP request", async () => {
  let complete,
    calls = 0;
  globalThis.fetch = () => {
    calls++;
    return new Promise((resolve) => {
      complete = resolve;
    });
  };
  const first = ensureAuth(),
    second = ensureAuth();
  assert.equal(calls, 1);
  complete(response({ id: 9, username: "sample" }));
  await Promise.all([first, second]);
  assert.equal(auth.user.id, 9);
});
test("a confirmed 401 caches the signed-out state instead of repeatedly requesting identity", async () => {
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return response(null, 401);
  };
  await ensureAuth();
  await ensureAuth();
  assert.equal(calls, 1);
  assert.equal(auth.ready, true);
  assert.equal(auth.user, null);
});
