// Explicit live Amap + Spring AI verification. Uses a fresh test account and model/API quota.
import assert from "node:assert/strict";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { localClient, testAccount } from "./local-api.mjs";

const mode = process.env.AGENT_VERIFY_MODE || "walking";
assert(["walking", "transit", "driving"].includes(mode), "Unsupported verification mode");
const keywords = mode === "walking" ? ["岳王庙", "曲院风荷"] : ["杭州东站", "岳王庙"];
const label = { walking: "步行", transit: "公交（含地铁）", driving: "自驾" }[mode];
const report = { at: new Date().toISOString(), travelMode: mode, passed: false, checks: {} };
const outputPrefix = mode === "walking" ? ".runtime/amap-agent" : ".runtime/amap-agent-" + mode;
const account = testAccount();
const client = localClient(process.env.AGENT_VERIFY_URL || "http://127.0.0.1:3200");
await mkdir(".runtime", { recursive: true });
const settings = await readFile("travel-java-server/.env", "utf8");
const key = settings.match(/^\s*AMAP_API_KEY\s*=([^\r\n]*)/m)?.[1].trim();
assert(key, "AMAP_API_KEY is not configured");
async function amap(path, parameters) {
  const url = new URL("https://restapi.amap.com" + path);
  for (const [name, value] of Object.entries({ ...parameters, key })) url.searchParams.set(name, value);
  let response;
  try { response = await fetch(url, { signal: AbortSignal.timeout(15000) }); }
  catch { throw Error("Amap connection failed or timed out"); }
  assert(response.ok, "Amap HTTP status " + response.status);
  const body = await response.json();
  // Retain only the documented numeric error code; never log credential-bearing URLs/errors.
  if (body.status !== "1") throw Error("Amap rejected the request; infocode=" + String(body.infocode).replace(/[^0-9]/g, ""));
  return body;
}
async function wait(job) {
  const start = performance.now();
  while (["QUEUED", "RUNNING"].includes(job.status)) {
    assert(performance.now() - start < 260000, "Agent verification deadline exceeded");
    await new Promise(resolve => setTimeout(resolve, 1500));
    job = await client.api("/api/agent/jobs/" + job.id);
  }
  report.job = job;
  assert.equal(job.status, "READY", "Agent did not complete: " + job.errorCode + " " + job.message);
  return job;
}
try {
  const places = [];
  for (const keyword of keywords) {
    const value = await amap("/v3/place/text", { city: "杭州", citylimit: "true", keywords: keyword, offset: "3", page: "1", extensions: "base" });
    const poi = value.pois?.find(p => p.location && p.id && p.name.includes(keyword));
    assert(poi, "Amap search returned no matching place");
    places.push({ id: poi.id, name: poi.name, location: poi.location });
  }
  report.places = places;
  const parameters = { origin: places[0].location, destination: places[1].location };
  if (mode === "transit") Object.assign(parameters, { city: "杭州", cityd: "杭州", strategy: "0", extensions: "base" });
  if (mode === "driving") Object.assign(parameters, { originid: places[0].id, destinationid: places[1].id, strategy: "0", extensions: "base" });
  const routed = await amap(mode === "transit" ? "/v3/direction/transit/integrated" : "/v3/direction/" + mode, parameters);
  const route = mode === "transit" ? routed.route?.transits?.[0] : routed.route?.paths?.[0];
  assert(route && Number(route.duration) >= 0, "Amap did not return a usable route");
  report.route = { profile: mode, from: places[0].name, to: places[1].name,
    meters: route.distance ? Number(route.distance) : null, seconds: Number(route.duration) };
  report.checks.realPlaceSearch = true;
  report.checks.realSelectedRoute = true;
  console.log(JSON.stringify({ stage: "amap", passed: true, places: places.map(p => p.name), route: report.route }));

  await client.register(account);
  await writeFile(outputPrefix + "-live-account.json", JSON.stringify(account) + "\n");
  const capabilities = await client.api("/api/agent/capabilities");
  assert.equal(capabilities.mapsConfigured, true);
  const started = performance.now();
  const job = await wait(await client.api("/api/agent/jobs", "POST", {
    requestId: crypto.randomUUID(), city: "杭州", days: 1, budget: 1000, travelMode: mode,
    instruction: "单人一日游，选择" + label + "出行。上午" + keywords[0] + "、下午" + keywords[1]
      + "、晚上酒店自由休息。先调用estimate_budget核算费用，交通预留200元，再用search_places分别查询这两个地点，"
      + "使用实际返回的placeId安排上午和下午；必须调用route_places查询上午到下午的所选方式路线。"
      + "不要搜索其他地点，晚上休息不需要地点ID。门票只作估算。调用validate_itinerary完成后结束。"
  }));
  report.durationMs = Math.round(performance.now() - started);
  for (const tool of ["search_places", "route_places", "estimate_budget", "validate_itinerary"])
    assert(job.traces.some(t => t.tool === tool && t.status === "ok"), "Missing successful tool " + tool);
  const day = job.plan.dailyItinerary[0];
  assert(day.morning.placeId && day.afternoon.placeId && day.afternoon.route);
  assert.equal(day.afternoon.route.fromId, day.morning.placeId);
  assert.equal(day.afternoon.route.toId, day.afternoon.placeId);
  assert.equal(job.travelMode, mode);
  assert.equal(job.plan.travelMode, mode);
  assert.equal(day.afternoon.route.profile, mode);
  assert(day.afternoon.route.meters > 0 && day.afternoon.route.seconds > 0);
  assert.equal(day.afternoon.travelMinutes, Math.ceil(day.afternoon.route.seconds / 60));
  assert.equal(day.afternoon.route.source, "高德地图");
  assert(day.morning.sourceUrl?.startsWith("https://www.amap.com/detail/"));
  assert(job.validation.valid);
  if (mode === "transit") {
    assert(day.afternoon.transportation.includes("公交"));
    if (day.afternoon.route.estimatedFare != null)
      assert(job.plan.budgetBreakdown.transportation >= day.afternoon.route.estimatedFare);
  }
  if (mode === "driving") assert.equal(day.afternoon.route.estimatedFare, null);
  report.checks.agentUsesRealMapTools = true;
  const saved = await client.api("/api/agent/jobs/" + job.id + "/save", "POST", {});
  const restored = await client.api("/api/trips/" + saved.trip.id);
  assert.deepEqual(restored.plan, saved.plan);
  report.tripId = saved.trip.id;
  report.checks.sourceAndRoutePersisted = true;
  report.passed = true;
} catch (error) {
  report.error = String(error.message).replaceAll(key, "[REDACTED]");
  process.exitCode = 1;
} finally {
  const safe = JSON.stringify(report, null, 2).replaceAll(key, "[REDACTED]");
  await writeFile(outputPrefix + "-live-verification.json", safe + "\n");
  console.log(JSON.stringify({ passed: report.passed, mode, checks: report.checks,
    durationMs: report.durationMs, error: report.error, tripId: report.tripId }));
}

