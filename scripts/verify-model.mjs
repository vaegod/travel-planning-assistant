// Opt-in live smoke test: creates a trip and a conversation and consumes model tokens.
// Run from the project root with Node 24. Never prints credentials or cookies.
import assert from "node:assert/strict";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { readChatResponse } from "../travel-h5-java/src/utils/chatStream.ts";

const project = fileURLToPath(new URL("../", import.meta.url));
const root = new URL(
  process.env.TRAVEL_SMOKE_BASE_URL || "http://127.0.0.1:3200",
);
assert(
  ["127.0.0.1", "localhost", "[::1]"].includes(root.hostname),
  "Smoke test only sends demo credentials to localhost",
);
const output = resolve(
  project,
  process.env.TRAVEL_SMOKE_OUTPUT || ".runtime/qwen-switch-live.json",
);
const account =
  process.env.TRAVEL_SMOKE_USERNAME && process.env.TRAVEL_SMOKE_PASSWORD
    ? {
        username: process.env.TRAVEL_SMOKE_USERNAME,
        password: process.env.TRAVEL_SMOKE_PASSWORD,
      }
    : JSON.parse(
        await readFile(resolve(project, ".runtime/demo-account.json"), "utf8"),
      );
const settings = Object.fromEntries(
  (await readFile(resolve(project, "travel-java-server/.env"), "utf8"))
    .split(/\r?\n/)
    .filter((line) => /^[A-Z_]+=/.test(line))
    .map((line) => [
      line.slice(0, line.indexOf("=")),
      line.slice(line.indexOf("=") + 1),
    ]),
);
assert.equal(settings.LLM_MODEL, "Qwen/Qwen3.5-35B-A3B");
assert.equal(settings.LLM_ENABLE_THINKING, "false");
const record = {
  at: new Date().toISOString(),
  model: settings.LLM_MODEL,
  enableThinking: false,
  checks: {},
  turns: [],
};
const cookies = new Map();
let csrf, activeJob;
async function flush() {
  await mkdir(resolve(project, ".runtime"), { recursive: true });
  await writeFile(output, JSON.stringify(record, null, 2) + "\n");
}
async function fetchLocal(path, method = "GET", body, timeout = 15000) {
  const headers = new Headers({
    Accept:
      path === "/api/travel/chat" ? "text/event-stream" : "application/json",
    Cookie: [...cookies].map(([key, value]) => `${key}=${value}`).join("; "),
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
  for (const entry of response.headers.getSetCookie()) {
    const pair = entry.split(";", 1)[0],
      split = pair.indexOf("=");
    cookies.set(pair.slice(0, split), pair.slice(split + 1));
  }
  return response;
}
async function api(path, method = "GET", body) {
  const response = await fetchLocal(path, method, body);
  if (!response.ok)
    throw Error(`Local API failed: ${method} ${path}, HTTP ${response.status}`);
  const envelope = await response.json();
  assert.equal(envelope.success, true);
  return envelope.data;
}
async function login() {
  csrf = await api("/api/auth/csrf");
  await api("/api/auth/login", "POST", account);
  csrf = await api("/api/auth/csrf");
}
async function awaitJob(job) {
  activeJob = job.id;
  const begin = performance.now();
  while (["QUEUED", "RUNNING"].includes(job.status)) {
    assert(
      performance.now() - begin < 185000,
      "Planning job exceeded total deadline",
    );
    await new Promise((resolve) => setTimeout(resolve, 700));
    job = await api(`/api/planning/jobs/${job.id}`);
  }
  activeJob = undefined;
  return { job, elapsedMs: Math.round(performance.now() - begin) };
}
async function turn(message) {
  const requestId = crypto.randomUUID(),
    begin = performance.now();
  const result = {
    requestId,
    message,
    answer: "",
    chunks: 0,
    firstChunkMs: null,
  };
  record.turns.push(result);
  const response = await fetchLocal(
    "/api/travel/chat",
    "POST",
    { requestId, conversationId: record.conversationId, message },
    90000,
  );
  await readChatResponse(response, requestId, (chunk) => {
    result.firstChunkMs ??= Math.round(performance.now() - begin);
    result.chunks++;
    result.answer += chunk;
  });
  result.elapsedMs = Math.round(performance.now() - begin);
  result.completed = true;
  assert(result.answer.trim() && result.chunks > 0);
  assert(
    !result.answer.includes("<think>"),
    "Reasoning tags appeared in visible answer",
  );
  await flush();
  return result.answer;
}
const matches = (text, expressions) =>
  expressions.every((expression) => expression.test(text));
try {
  await flush();
  await login();
  const start = await api("/api/planning/jobs", "POST", {
    city: "杭州",
    days: 3,
    budget: 2000,
  });
  record.planningJobId = start.id;
  await flush();
  const planned = await awaitJob(start);
  record.planning = planned;
  await flush();
  assert.equal(
    planned.job.status,
    "READY",
    `Planning failed: ${planned.job.errorCode || planned.job.status}; ${planned.job.message || ""}`,
  );
  const plan = planned.job.plan;
  assert.equal(plan.city, "杭州");
  assert.equal(plan.days, 3);
  assert.equal(plan.totalBudget, 2000);
  assert.equal(plan.evidence.status, "validated");
  const places = plan.dailyItinerary.flatMap((day) => [
    day.morning,
    day.afternoon,
    day.evening,
  ]);
  assert.equal(new Set(places.map((place) => place.placeId)).size, 9);
  assert(
    places.every(
      (place) =>
        place.sourceUrl.startsWith("https://www.openstreetmap.org/") &&
        place.queriedAt,
    ),
  );
  assert.equal(
    Math.round(
      Object.values(plan.budgetBreakdown).reduce((a, b) => a + b, 0) * 100,
    ),
    Math.round(plan.estimatedTotal * 100),
  );
  assert(plan.estimatedTotal <= plan.totalBudget);
  record.checks.planning = true;
  record.savedTrip = await api(`/api/planning/jobs/${start.id}/save`, "POST", {
    title: "Qwen切换验收 · 杭州3天",
  });
  record.tripId = record.savedTrip.trip.id;
  assert.equal(record.savedTrip.trip.currentVersion, 1);
  record.checks.save = true;
  const conversation = await api("/api/conversations", "POST", {
    tripId: record.tripId,
  });
  record.conversationId = conversation.id;
  await flush();
  const one = await turn(
    "我计划去杭州玩3天，预算2000元，不开车，喜欢博物馆。我的同行人叫小岚。请记住这些条件，用100字以内给建议，不要声称已保存修改。",
  );
  assert(
    matches(one, [/杭州/, /博物馆|美术馆|展馆/]),
    "First reply did not follow destination and museum preference",
  );
  const two = await turn(
    "预算改成1500元，其余条件不变。请用100字以内说清城市、天数、预算和交通偏好。",
  );
  assert(
    matches(two, [
      /杭州/,
      /3|三/,
      /1[，,]?500/,
      /不开车|不自驾|公交|地铁|公共交通|步行/,
    ]),
    "Second reply did not preserve updated constraints",
  );
  const three = await turn(
    "我的同行人叫什么？再复述当前城市、天数和预算，100字以内。",
  );
  assert(
    matches(three, [/小岚/, /杭州/, /3|三/, /1[，,]?500/]),
    "Third reply did not retain earlier unstructured history and new budget",
  );
  record.checks.multiTurn = true;
  let messages;
  for (let attempt = 0; attempt < 10; attempt++) {
    messages = await api(
      `/api/conversations/${record.conversationId}/messages`,
    );
    if (
      messages.items.length === 6 &&
      messages.items.every((item) => item.status === "completed")
    )
      break;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.equal(messages.items.length, 6);
  assert(messages.items.every((item) => item.status === "completed"));
  record.messages = messages.items;
  record.context = (
    await api(`/api/conversations/${record.conversationId}`)
  ).context;
  assert.equal(record.context.city, "杭州");
  assert.equal(record.context.days, 3);
  assert.equal(record.context.budget, 1500);
  const afterChat = await api(`/api/trips/${record.tripId}`);
  assert.equal(afterChat.trip.currentVersion, 1);
  assert.equal(afterChat.plan.totalBudget, 2000);
  record.checks.chatDoesNotOverwriteTrip = true;
  await api("/api/auth/logout", "POST", {});
  await login();
  const restored = await api(
    `/api/conversations/${record.conversationId}/messages`,
  );
  assert.deepEqual(
    restored.items.map((item) => item.content),
    record.messages.map((item) => item.content),
  );
  assert.equal(
    (await api(`/api/trips/${record.tripId}`)).trip.currentVersion,
    1,
  );
  record.checks.reloginPersistence = true;
  record.passed = true;
  await flush();
  console.log(
    JSON.stringify(
      {
        passed: true,
        model: record.model,
        tripId: record.tripId,
        conversationId: record.conversationId,
        planningMs: planned.elapsedMs,
        estimatedTotal: plan.estimatedTotal,
        turns: record.turns.map(({ firstChunkMs, elapsedMs, chunks }) => ({
          firstChunkMs,
          elapsedMs,
          chunks,
        })),
        checks: record.checks,
      },
      null,
      2,
    ),
  );
} catch (error) {
  record.passed = false;
  record.error = error.message;
  if (activeJob)
    await fetchLocal(`/api/planning/jobs/${activeJob}`, "DELETE").catch(
      () => {},
    );
  await flush();
  console.error(
    `Live verification failed: ${error.message}. See the local result JSON.`,
  );
  process.exitCode = 1;
}
