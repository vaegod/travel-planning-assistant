import assert from "node:assert/strict";
import { readChatResponse } from "../travel-h5-java/src/utils/chatStream.ts";

export function localClient(address) {
  const root = new URL(address);
  assert(
    ["localhost", "127.0.0.1", "[::1]"].includes(root.hostname),
    "Credentials are restricted to the local application",
  );
  const cookies = new Map();
  let csrf;
  async function request(path, method = "GET", body, timeout = 15000) {
    const headers = new Headers({
      Accept:
        path === "/api/travel/chat" ? "text/event-stream" : "application/json",
      Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join("; "),
    });
    if (body !== undefined) headers.set("Content-Type", "application/json");
    if (csrf && !["GET", "HEAD"].includes(method))
      headers.set(csrf.headerName, csrf.token);
    const response = await fetch(new URL(path, root), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(timeout),
    });
    for (const value of response.headers.getSetCookie()) {
      const pair = value.split(";", 1)[0],
        split = pair.indexOf("=");
      cookies.set(pair.slice(0, split), pair.slice(split + 1));
    }
    return response;
  }
  async function reply(path, method = "GET", body) {
    const response = await request(path, method, body);
    return {
      status: response.status,
      headers: response.headers,
      body: await response.json(),
    };
  }
  async function api(path, method = "GET", body) {
    const value = await reply(path, method, body);
    assert.equal(
      value.status,
      200,
      `${method} ${path} returned ${value.status}: ${value.body.errorCode || "unexpected response"}`,
    );
    assert.equal(value.body.success, true);
    return value.body.data;
  }
  async function login(account) {
    csrf = await api("/api/auth/csrf");
    await api("/api/auth/login", "POST", account);
    csrf = await api("/api/auth/csrf");
  }
  async function register(account) {
    csrf = await api("/api/auth/csrf");
    await api("/api/auth/register", "POST", account);
    await login(account);
  }
  async function chat(conversationId, message) {
    const requestId = crypto.randomUUID(),
      start = performance.now();
    let answer = "",
      chunks = 0,
      firstContentMs = null;
    const response = await request(
      "/api/travel/chat",
      "POST",
      { conversationId, requestId, message },
      150000,
    );
    await readChatResponse(response, requestId, (chunk) => {
      firstContentMs ??= Math.round(performance.now() - start);
      chunks++;
      answer += chunk;
    });
    return {
      requestId,
      message,
      answer,
      chunks,
      firstContentMs,
      durationMs: Math.round(performance.now() - start),
    };
  }
  return { api, reply, request, login, register, chat };
}

export async function waitForJob(
  client,
  job,
  limit = 185000,
  prefix = "/api/planning/jobs",
) {
  const start = performance.now();
  while (["QUEUED", "RUNNING"].includes(job.status)) {
    assert(
      performance.now() - start < limit,
      "Planning exceeded evaluation deadline",
    );
    await new Promise((r) => setTimeout(r, 80));
    job = await client.api(`${prefix}/${job.id}`);
  }
  return job;
}
export const testAccount = () => ({
  username: "eval_" + crypto.randomUUID().replaceAll("-", "").slice(0, 20),
  password: "Eval-" + crypto.randomUUID(),
});
