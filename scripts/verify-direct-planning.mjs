// Opt-in real-model verification of the current direct-only planning flow.
import assert from "node:assert/strict";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { localClient, testAccount } from "./local-api.mjs";
const address = process.env.DIRECT_VERIFY_URL || "http://127.0.0.1:3200",
  client = localClient(address),
  account = testAccount();
const kind = new URL(address).protocol === "https:" ? "https" : "native";
const report = {
  at: new Date().toISOString(),
  origin: address,
  flow: "direct-model-only",
  request: { city: "杭州", days: 3, budget: 2000 },
  checks: {},
};
await mkdir(".runtime", { recursive: true });
const file = `.runtime/direct-planning-${kind}.json`;
try {
  await client.register(account);
  const blocked = await client.reply("/api/planning/jobs", "POST", {
    city: "杭州",
    days: 1,
    budget: 1000,
  });
  assert.equal(blocked.status, 410);
  report.checks.externalWorkflowDisabled = true;
  const started = performance.now();
  const generated = await client.request(
    "/api/travel/recommend",
    "POST",
    report.request,
    125000,
  );
  assert.equal(generated.status, 200);
  const envelope = await generated.json();
  assert.equal(envelope.success, true);
  report.plan = envelope.data;
  report.durationMs = Math.round(performance.now() - started);
  assert.equal(report.plan.success, true);
  assert.equal(report.plan.city, "杭州");
  assert.equal(report.plan.days, 3);
  assert.equal(report.plan.totalBudget, 2000);
  assert.equal(report.plan.dailyItinerary.length, 3);
  assert(!report.plan.evidence);
  for (const day of report.plan.dailyItinerary)
    for (const slot of [day.morning, day.afternoon, day.evening])
      assert(slot.spot && !slot.placeId && !slot.sourceUrl && !slot.route);
  report.checks.completeUnverifiedModelPlan = true;
  const trip = await client.api("/api/trips", "POST", {
    title: "AI直接规划验收",
    plan: report.plan,
  });
  report.tripId = trip.trip.id;
  await client.api("/api/auth/logout", "POST", {});
  await client.login(account);
  assert.deepEqual(await client.api(`/api/trips/${trip.trip.id}`), trip);
  report.checks.saveAndRelogin = true;
  const conversation = await client.api("/api/conversations", "POST", {
    tripId: trip.trip.id,
  });
  assert.equal(conversation.tripId, trip.trip.id);
  report.checks.linkedConversation = true;
  report.passed = true;
  await writeFile(
    `.runtime/direct-planning-${kind}-account.json`,
    JSON.stringify(account) + "\n",
  );
  await writeFile(file, JSON.stringify(report, null, 2) + "\n");
  console.log(
    JSON.stringify({
      passed: true,
      flow: report.flow,
      durationMs: report.durationMs,
      tripId: report.tripId,
      checks: report.checks,
    }),
  );
} catch (error) {
  report.passed = false;
  report.error = error.message;
  await writeFile(file, JSON.stringify(report, null, 2) + "\n");
  console.error(error.message);
  process.exitCode = 1;
}
