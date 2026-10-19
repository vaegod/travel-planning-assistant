import assert from "node:assert/strict";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { startProvider } from "./eval-provider.mjs";
import { localClient, waitForJob, testAccount } from "./local-api.mjs";

const root = fileURLToPath(new URL("../", import.meta.url)),
  backend = resolve(root, "travel-java-server");
const mode = process.argv.includes("--live") ? "live" : "fixture";
const split =
  process.argv.find((arg) => arg.startsWith("--split="))?.split("=")[1] ||
  "all";
const source = await readFile(resolve(root, "eval/cases.json"), "utf8"),
  dataset = JSON.parse(source);
const cases = dataset.cases.filter((c) => split === "all" || c.split === split);
assert(cases.length > 0, "Unknown evaluation split");
assert(
  mode !== "live" || split === "holdout",
  "Live evaluation is restricted to the fixed holdout subset",
);
let localSettings = {};
try {
  localSettings = Object.fromEntries(
    (await readFile(resolve(backend, ".env"), "utf8"))
      .split(/\r?\n/)
      .filter((line) => /^[A-Z_]+=/.test(line))
      .map((line) => [
        line.slice(0, line.indexOf("=")),
        line.slice(line.indexOf("=") + 1),
      ]),
  );
} catch {}
const settings = { ...localSettings, ...process.env };
assert(
  settings.DB_TEST_URL && settings.DB_TEST_URL.includes("travel_ai_test"),
  "Evaluation requires a separate travel_ai_test database URL",
);
if (mode === "live")
  assert(settings.LLM_API_KEY, "Live evaluation needs a model key");
await mkdir(resolve(root, ".runtime"), { recursive: true });
const report = {
  dataset: dataset.version,
  scope:
    "legacy-map-workflow-only; not the current default direct planning flow",
  datasetSha256: createHash("sha256").update(source).digest("hex"),
  at: new Date().toISOString(),
  mode,
  split,
  model:
    mode === "live"
      ? settings.LLM_MODEL || "Qwen/Qwen3.5-35B-A3B"
      : "fixture-model",
  mapData:
    "Recorded OSM fixture replay + fixed route fixture, not current live map queries",
  promptVersions: ["planning-select-v2", "travel-chat-v3"],
  results: [],
};
const output = resolve(root, `.runtime/eval-${mode}-${split}.json`),
  logs = resolve(root, `.runtime/eval-${mode}-${split}.log`);
const port = Number(process.env.EVAL_PORT || 3211),
  address = `http://127.0.0.1:${port}`;
const provider = await startProvider({
  live:
    mode === "live"
      ? {
          baseUrl: settings.LLM_BASE_URL || "https://api.siliconflow.cn/v1",
          key: settings.LLM_API_KEY,
        }
      : null,
});
const origin = `http://127.0.0.1:${provider.port}`;
const java = process.env.JAVA_HOME
  ? resolve(
      process.env.JAVA_HOME,
      "bin",
      process.platform === "win32" ? "java.exe" : "java",
    )
  : "java";
let logText = "",
  stopped = false;
const app = spawn(
  java,
  [
    ...(process.platform === "win32"
      ? ["-Djavax.net.ssl.trustStoreType=Windows-ROOT"]
      : []),
    "-jar",
    resolve(backend, "target/travel-java-server-0.0.1-SNAPSHOT.jar"),
  ],
  {
    cwd: backend,
    env: {
      ...process.env,
      DB_URL: settings.DB_TEST_URL,
      DB_USERNAME: settings.DB_USERNAME,
      DB_PASSWORD: settings.DB_PASSWORD,
      SERVER_PORT: String(port),
      SERVER_ADDRESS: "127.0.0.1",
      LLM_BASE_URL: origin,
      LLM_API_KEY: "fixture-relay-only",
      LLM_MODEL: report.model,
      LLM_ENABLE_THINKING: "false",
      OVERPASS_URL: origin + "/places",
      OSRM_URL: origin,
      PLANNING_CACHE_DIR: "",
      PLANNING_EXTERNAL_ENABLED: "true",
      PLANNING_CACHE_TTL_SECONDS: "0",
      FORWARD_HEADERS_STRATEGY: "none",
      SERVER_SERVLET_SESSION_COOKIE_SECURE: "false",
    },
    windowsHide: true,
    stdio: ["ignore", "pipe", "pipe"],
  },
);
app.stdout.on("data", (part) => {
  logText += part.toString();
});
app.stderr.on("data", (part) => {
  logText += part.toString();
});
app.on("exit", () => {
  stopped = true;
});
const a = localClient(address),
  b = localClient(address);
const clone = (value) => JSON.parse(JSON.stringify(value)),
  money = (value) => Math.round(Number(value) * 100);
const flush = async () => {
  await writeFile(output, JSON.stringify(report, null, 2) + "\n");
  await writeFile(logs, logText);
};
const planInput = { city: "杭州", days: 3, budget: 2000 };
async function draft(input = planInput) {
  return waitForJob(a, await a.api("/api/planning/jobs", "POST", input));
}
async function saved() {
  const job = await draft();
  assert.equal(job.status, "READY");
  return a.api(`/api/planning/jobs/${job.id}/save`, "POST", {});
}
async function revision(base, c) {
  return waitForJob(
    a,
    await a.api(`/api/planning/trips/${base.trip.id}/revisions`, "POST", {
      baseVersion: base.trip.currentVersion,
      action: c.action,
      day: c.day,
      budget: c.budget,
      instruction: "只修改指定范围，其他要求保持不变",
    }),
  );
}
function checkPlan(plan, input) {
  assert.equal(plan.city, input.city);
  assert.equal(plan.days, input.days);
  assert.equal(plan.totalBudget, input.budget);
  assert.equal(plan.evidence.status, "validated");
  assert.equal(plan.dailyItinerary.length, input.days);
  const slots = plan.dailyItinerary
    .flatMap((d) => [d.morning, d.afternoon, d.evening])
    .filter((s) => !s.rest);
  assert.equal(new Set(slots.map((s) => s.placeId)).size, slots.length);
  assert(slots.every((s) => s.sourceUrl && s.queriedAt));
  assert.equal(
    Object.values(plan.budgetBreakdown).reduce(
      (sum, value) => sum + money(value),
      0,
    ),
    money(plan.estimatedTotal),
  );
  assert(plan.estimatedTotal <= input.budget);
}
async function execute(c, row) {
  if (c.type === "invalid") {
    const r = await a.reply("/api/planning/jobs", "POST", c.input);
    assert.equal(r.status, 400);
    assert.equal(provider.state.calls.model, 0);
    return;
  }
  if (c.type === "empty-chat") {
    const conv = await a.api("/api/conversations", "POST", {});
    assert.equal(
      (
        await a.reply("/api/travel/chat", "POST", {
          requestId: crypto.randomUUID(),
          conversationId: conv.id,
          message: " ",
        })
      ).status,
      400,
    );
    return;
  }
  if (c.type === "failure") {
    const job = await draft({ city: "杭州", days: 1, budget: 2000 });
    row.job = job;
    assert.equal(job.status, "ERROR");
    assert.equal(job.errorCode, c.error);
    assert.equal(job.plan, null);
    assert.equal(
      (await a.reply(`/api/planning/jobs/${job.id}/save`, "POST", {})).status,
      409,
    );
    if (c.modelCalls) assert.equal(provider.state.calls.model, c.modelCalls);
    return;
  }
  if (c.type === "plan") {
    const job = await draft(c.input);
    row.job = job;
    assert.equal(job.status, "READY", job.errorCode || job.status);
    checkPlan(job.plan, c.input);
    return;
  }
  if (c.type === "ownership") {
    const job = await draft();
    assert.equal((await b.reply(`/api/planning/jobs/${job.id}`)).status, 404);
    assert.equal(
      (await b.reply(`/api/planning/jobs/${job.id}`, "DELETE")).status,
      404,
    );
    assert.equal(
      (await b.reply(`/api/planning/jobs/${job.id}/save`, "POST", {})).status,
      404,
    );
    return;
  }
  if (c.type === "revise") {
    const base = await saved(),
      before = clone(base.plan);
    const job = await revision(base, c);
    row.job = job;
    if (c.error) {
      assert.equal(job.status, "ERROR");
      assert.equal(job.errorCode, c.error);
      assert.equal(
        (await a.api(`/api/trips/${base.trip.id}`)).trip.currentVersion,
        1,
      );
      return;
    }
    assert.equal(job.status, "READY", job.errorCode || job.status);
    checkPlan(job.plan, {
      ...planInput,
      budget: c.action === "budget" ? c.budget : 2000,
    });
    if (c.action === "budget")
      assert.deepEqual(job.plan.dailyItinerary, before.dailyItinerary);
    else
      for (let day = 0; day < 3; day++)
        if (day !== c.day - 1)
          assert.deepEqual(
            job.plan.dailyItinerary[day],
            before.dailyItinerary[day],
          );
    if (c.action === "indoor") {
      const day = job.plan.dailyItinerary[c.day - 1];
      assert(day.morning.indoor && day.afternoon.indoor && day.evening.rest);
    }
    assert.equal(
      (await a.api(`/api/trips/${base.trip.id}`)).trip.currentVersion,
      1,
    );
    const updated = await a.api(
      `/api/planning/jobs/${job.id}/save`,
      "POST",
      {},
    );
    assert.equal(updated.trip.currentVersion, 2);
    assert.deepEqual(
      (await a.api(`/api/trips/${base.trip.id}?version=1`)).plan,
      before,
    );
    return;
  }
  if (c.type === "idempotent") {
    const job = await draft();
    const one = await a.api(`/api/planning/jobs/${job.id}/save`, "POST", {}),
      two = await a.api(`/api/planning/jobs/${job.id}/save`, "POST", {});
    assert.equal(one.trip.id, two.trip.id);
    assert.equal(two.versions.length, 1);
    return;
  }
  if (c.type === "conflict") {
    const base = await saved(),
      one = await revision(base, { action: "budget", budget: 1500 }),
      two = await revision(base, { action: "budget", budget: 1800 });
    await a.api(`/api/planning/jobs/${one.id}/save`, "POST", {});
    assert.equal(
      (await a.reply(`/api/planning/jobs/${two.id}/save`, "POST", {})).status,
      409,
    );
    assert.equal(
      (await a.api(`/api/trips/${base.trip.id}`)).versions.length,
      2,
    );
    return;
  }
  if (c.type === "chat") {
    const conv = await a.api("/api/conversations", "POST", {});
    const initialContext = (await a.api(`/api/conversations/${conv.id}`)).context;
    row.turns = [];
    const say = async (message) => {
      const answer = await a.chat(conv.id, message);
      row.turns.push(answer);
      return answer.answer;
    };
    if (c.scenario === "missing") {
      const answer = await say(
        "我想出去玩，请先问我必要的信息，不要自行设定目的地、天数和预算。",
      );
      assert(
        /城市|目的地/.test(answer) &&
          /天数|几天|时长/.test(answer) &&
          /预算/.test(answer),
      );
      return;
    }
    await say(
      "我准备去杭州玩3天，预算2000元，不开车，喜欢博物馆。我的同行人叫小岚。请用100字以内回答，记住这些要求。",
    );
    if (c.scenario === "isolation") {
      const other = await a.api("/api/conversations", "POST", {});
      const reply = await a.chat(
        other.id,
        "我的同行人叫什么？如果不知道就明确说不知道，不要编造。",
      );
      row.turns.push(reply);
      assert(!reply.answer.includes("小岚"));
      assert(/不知|没有|未|告诉|告知|不清楚/.test(reply.answer));
      return;
    }
    const second = await say(
      "预算改成1500元，其余条件不变。请用100字以内复述城市、天数、预算和交通偏好。",
    );
    assert(
      /杭州/.test(second) &&
        /3|三/.test(second) &&
        /1[，,]?500/.test(second) &&
        /不开车|不自驾|公交|地铁|步行|公共交通/.test(second),
    );
    if (c.scenario === "memory") {
      const third = await say(
        "我的同行人叫什么？再说说现在城市、天数和预算，100字以内。",
      );
      assert(
        /小岚/.test(third) && /杭州/.test(third) && /1[，,]?500/.test(third),
      );
    }
    const context = (await a.api(`/api/conversations/${conv.id}`)).context;
    // Chat text supplies history to the model; it must not silently rewrite reference fields.
    assert.deepEqual(context, initialContext);
    const messages = await a.api(`/api/conversations/${conv.id}/messages`);
    assert.equal(messages.items.length, row.turns.length * 2);
    assert(messages.items.every((m) => m.status === "completed"));
    return;
  }
  throw Error("Unknown case type");
}
try {
  for (let i = 0; i < 80; i++) {
    if (stopped) throw Error("Evaluation backend exited during startup");
    try {
      const r = await fetch(address + "/api/health", {
        signal: AbortSignal.timeout(1000),
      });
      if (r.ok) break;
    } catch {}
    if (i === 79) throw Error("Evaluation backend not ready");
    await new Promise((r) => setTimeout(r, 250));
  }
  await a.register(testAccount());
  await b.register(testAccount());
  for (const c of cases) {
    provider.state.mode = c.mode || "ok";
    provider.state.calls = { places: 0, route: 0, model: 0 };
    const start = performance.now(),
      logStart = logText.length,
      row = { id: c.id, name: c.name, split: c.split, type: c.type };
    try {
      await execute(c, row);
      row.passed = true;
    } catch (error) {
      row.passed = false;
      row.error = error.message;
    }
    row.durationMs = Math.round(performance.now() - start);
    row.providerCalls = { ...provider.state.calls };
    row.modelCalls = [
      ...logText.slice(logStart).matchAll(/model_call (\{[^\r\n]+\})/g),
    ].map((m) => JSON.parse(m[1]));
    report.results.push(row);
    await flush();
    console.log(
      `${row.passed ? "PASS" : "FAIL"} ${c.id} ${c.name}${row.error ? " " + row.error : ""}`,
    );
  }
  const calls = report.results.flatMap((r) => r.modelCalls);
  report.summary = {
    total: report.results.length,
    passed: report.results.filter((r) => r.passed).length,
    failed: report.results.filter((r) => !r.passed).length,
    modelCalls: calls.length,
    usageKnownCalls: calls.filter((c) => c.usageComplete).length,
    knownInputTokens: calls.reduce((s, c) => s + (c.inputTokens || 0), 0),
    knownOutputTokens: calls.reduce((s, c) => s + (c.outputTokens || 0), 0),
  };
  await flush();
  if (report.summary.failed) process.exitCode = 1;
  console.log(JSON.stringify(report.summary));
} catch (error) {
  report.error = error.message;
  await flush();
  console.error(error.message);
  process.exitCode = 1;
} finally {
  app.kill();
  await provider.close();
  await flush();
}
