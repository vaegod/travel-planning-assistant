import test, { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { request, resetCsrf } from "../src/utils/http.ts";
import { mergeMessages } from "../src/utils/messageState.ts";
import { safeRedirect } from "../src/utils/redirect.ts";
const originalFetch = globalThis.fetch;
const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });
beforeEach(() => resetCsrf());
afterEach(() => {
  globalThis.fetch = originalFetch;
  resetCsrf();
});

test("explicit request cancellation propagates to fetch and is distinct from timeout", async () => {
  let started;
  const ready=new Promise(resolve=>{started=resolve;});
  globalThis.fetch=(url,options)=>new Promise((resolve,reject)=>{
    started();options.signal.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError')),{once:true});
  });
  const controller=new AbortController();
  const pending=request('/api/trips','GET',undefined,130000,controller.signal);
  await ready;controller.abort();
  await assert.rejects(pending,{code:'REQUEST_CANCELLED',status:499});
});

test("mutations send same-origin cookies and refresh CSRF only after explicit rejection", async () => {
  let tokenCount = 0,
    posts = 0;
  globalThis.fetch = async (url, options = {}) => {
    if (String(url).endsWith("/auth/csrf"))
      return json({
        success: true,
        data: { headerName: "X-CSRF-TOKEN", token: `token-${++tokenCount}` },
      });
    posts++;
    assert.equal(options.credentials, "same-origin");
    assert.equal(
      new Headers(options.headers).get("X-CSRF-TOKEN"),
      `token-${tokenCount}`,
    );
    return posts === 1
      ? json(
          { success: false, errorCode: "CSRF_INVALID", message: "stale" },
          403,
        )
      : json({ success: true, data: { saved: true } });
  };
  assert.deepEqual(await request("/api/trips", "POST", { plan: "example" }), {
    saved: true,
  });
  assert.equal(posts, 2);
  assert.equal(tokenCount, 2);
  await request("/api/trips", "POST", {});
  assert.equal(tokenCount, 2);
});
test("CSRF retry is bounded and unrelated 403 responses are never replayed", async () => {
  let posts = 0;
  globalThis.fetch = async (url) =>
    String(url).endsWith("/auth/csrf")
      ? json({
          success: true,
          data: { headerName: "X-CSRF-TOKEN", token: "one" },
        })
      : (posts++,
        json(
          { success: false, errorCode: "CSRF_INVALID", message: "invalid" },
          403,
        ));
  await assert.rejects(request("/api/trips", "POST", {}), {
    status: 403,
    code: "CSRF_INVALID",
  });
  assert.equal(posts, 2);
  posts = 0;
  resetCsrf();
  globalThis.fetch = async (url) =>
    String(url).endsWith("/auth/csrf")
      ? json({
          success: true,
          data: { headerName: "X-CSRF-TOKEN", token: "one" },
        })
      : (posts++,
        json({ success: false, errorCode: "FORBIDDEN", message: "no" }, 403));
  await assert.rejects(request("/api/trips", "POST", {}), {
    status: 403,
    code: "FORBIDDEN",
  });
  assert.equal(posts, 1);
});
test("expired login and version conflicts preserve structured errors without replay", async () => {
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return json(
      { success: false, errorCode: "AUTH_REQUIRED", message: "请先登录" },
      401,
    );
  };
  await assert.rejects(request("/api/trips"), {
    status: 401,
    code: "AUTH_REQUIRED",
  });
  assert.equal(calls, 1);
  globalThis.fetch = async () =>
    json(
      { success: false, errorCode: "VERSION_CONFLICT", message: "已更新" },
      409,
    );
  await assert.rejects(request("/api/trips"), {
    status: 409,
    code: "VERSION_CONFLICT",
  });
});
test("request timeout aborts a stuck request and is not reported as login failure", async () => {
  globalThis.fetch = (url, options) =>
    new Promise((resolve, reject) =>
      options.signal.addEventListener("abort", () =>
        reject(new DOMException("Aborted", "AbortError")),
      ),
    );
  await assert.rejects(request("/api/trips", "GET", undefined, 15), {
    status: 408,
    code: "REQUEST_TIMEOUT",
  });
});
test("malformed JSON responses do not become successful empty data", async () => {
  globalThis.fetch = async () => new Response("<html>error</html>");
  await assert.rejects(request("/api/trips"), { code: "HTTP_ERROR" });
});
test("restoring messages replaces optimistic rows instead of duplicating them", () => {
  const previous = [
    {
      id: "temp-user",
      seq: Number.MAX_SAFE_INTEGER - 1,
      requestId: "r",
      role: "user",
      content: "预算改成1500",
      status: "completed",
      statusText: "",
      timestamp: "now",
    },
    {
      id: "temp-ai",
      seq: Number.MAX_SAFE_INTEGER,
      requestId: "r",
      role: "ai",
      content: "半段",
      status: "streaming",
      statusText: "",
      timestamp: "now",
    },
  ];
  const saved = [
    {
      id: "db-user",
      seq: 10,
      requestId: "r",
      role: "user",
      content: "预算改成1500",
      status: "completed",
      createdAt: "2026-10-17T00:00:00Z",
    },
    {
      id: "db-ai",
      seq: 11,
      requestId: "r",
      role: "assistant",
      content: "完整回复",
      status: "completed",
      createdAt: "2026-10-17T00:00:01Z",
    },
  ];
  const merged = mergeMessages(previous, saved);
  assert.equal(merged.length, 2);
  assert.equal(merged[1].id, "db-ai");
  assert.equal(merged[1].content, "完整回复");
  assert.equal(merged[1].question, "预算改成1500");
});
test("restored stopped and failed messages retain content and retry question", () => {
  const saved = [
    {
      id: "1",
      seq: 1,
      requestId: "r",
      role: "user",
      content: "问题",
      status: "completed",
      createdAt: "now",
    },
    {
      id: "2",
      seq: 2,
      requestId: "r",
      role: "assistant",
      content: "部分回复",
      status: "stopped",
      createdAt: "now",
    },
  ];
  let merged = mergeMessages([], saved);
  assert.equal(merged[1].statusText, "已停止生成");
  assert.equal(merged[1].content, "部分回复");
  saved[1].status = "error";
  saved[1].errorMessage = "模型超时";
  merged = mergeMessages([], saved);
  assert.equal(merged[1].statusText, "模型超时");
  assert.equal(merged[1].question, "问题");
});
test("older pages merge chronologically without repeating cursor boundary messages", () => {
  const a = {
      id: "a",
      seq: 1,
      requestId: "one",
      role: "user",
      content: "旧消息",
      status: "completed",
      createdAt: "now",
    },
    b = {
      id: "b",
      seq: 2,
      requestId: "two",
      role: "user",
      content: "新消息",
      status: "completed",
      createdAt: "now",
    };
  assert.deepEqual(
    mergeMessages(mergeMessages([], [b]), [a, b]).map((m) => m.id),
    ["a", "b"],
  );
});
test("login redirect accepts local paths and rejects external or backslash paths", () => {
  assert.equal(
    safeRedirect("/chat/abc?from=history"),
    "/chat/abc?from=history",
  );
  for (const value of [
    "https://example.com",
    "//example.com",
    "/\\example.com",
    "/login",
    null,
    [],
  ])
    assert.equal(safeRedirect(value), "/");
});
