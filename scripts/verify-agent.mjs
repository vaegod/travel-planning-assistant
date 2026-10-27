// Opt-in live verification: consumes the configured model API, uses a new isolated account.
import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { localClient, testAccount } from "./local-api.mjs";

const client = localClient(process.env.AGENT_VERIFY_URL || "http://127.0.0.1:3200");
const account = testAccount();
const report = { at: new Date().toISOString(), checks: {}, passed: false };
await mkdir(".runtime", { recursive: true });
await writeFile(".runtime/agent-live-account.json", JSON.stringify(account) + "\n");
async function finish(job) {
  const start = performance.now();
  while (["RUNNING", "QUEUED"].includes(job.status)) {
    assert(performance.now() - start < 260000, "Agent job exceeded verification deadline");
    await new Promise(resolve => setTimeout(resolve, 1200));
    job = await client.api("/api/agent/jobs/" + job.id);
  }
  assert.equal(job.status, "READY", job.errorCode + ": " + job.message);
  assert(job.validation?.valid);
  return job;
}
try {
  await client.register(account);
  report.capabilities = await client.api("/api/agent/capabilities");
  let at = performance.now();
  const first = await finish(await client.api("/api/agent/jobs", "POST", {
    requestId: crypto.randomUUID(), city: "杭州", days: 2, budget: 2000,
    instruction: "喜欢博物馆和公园，节奏轻松。单人，两天一晚，预算包含目的地住宿，不含往返交通。"
  }));
  report.creation = { durationMs: Math.round(performance.now() - at), jobId: first.id,
    traces: first.traces, validation: first.validation, rounds: first.modelRounds };
  assert(first.traces.some(t => t.tool === "estimate_budget" && t.status === "ok"));
  assert(first.traces.some(t => t.tool === "validate_itinerary" && t.status === "ok"));
  const saved = await client.api("/api/agent/jobs/" + first.id + "/save", "POST", {});
  report.tripId = saved.trip.id;
  report.checks.toolCallingAndValidatedSave = true;
  at = performance.now();
  const second = await finish(await client.api("/api/agent/trips/" + saved.trip.id + "/revisions", "POST", {
    requestId: crypto.randomUUID(), baseVersion: saved.trip.currentVersion, day: 2, slot: "afternoon",
    instruction: "第二天下午换一个与原安排不同的博物馆，游览90分钟，不重复其他景点，不增加预算上限。"
  }));
  assert.notEqual(second.plan.dailyItinerary[1].afternoon.spot, saved.plan.dailyItinerary[1].afternoon.spot);
  for (let d = 0; d < saved.plan.days; d++)
    for (const slot of ["morning", "afternoon", "evening"])
      if (!(d === 1 && slot === "afternoon"))
        assert.deepEqual(second.plan.dailyItinerary[d][slot], saved.plan.dailyItinerary[d][slot]);
  report.revision = { durationMs: Math.round(performance.now() - at), jobId: second.id,
    traces: second.traces, changes: second.changes, validation: second.validation, rounds: second.modelRounds };
  const revised = await client.api("/api/agent/jobs/" + second.id + "/save", "POST", {});
  assert.equal(revised.trip.currentVersion, 2);
  assert.deepEqual((await client.api("/api/trips/" + saved.trip.id + "?version=1")).plan, saved.plan);
  report.checks.localPatchAndVersionHistory = true;
  await client.api("/api/auth/logout", "POST", {});
  await client.login(account);
  assert.deepEqual((await client.api("/api/trips/" + saved.trip.id)).plan, revised.plan);
  report.checks.reloginPersistence = true;
  report.passed = true;
} catch (error) {
  report.error = error.message;
  process.exitCode = 1;
} finally {
  await writeFile(".runtime/agent-live-verification.json", JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify({ passed: report.passed, checks: report.checks, error: report.error,
    tripId: report.tripId, creationMs: report.creation?.durationMs, revisionMs: report.revision?.durationMs }));
}
