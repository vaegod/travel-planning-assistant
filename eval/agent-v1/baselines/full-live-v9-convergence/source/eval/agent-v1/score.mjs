// Independent evaluation rules. Does not import application validators or planning code.
import { isDeepStrictEqual } from "node:util";
const slots = ["morning", "afternoon", "evening"];
const cents = value => typeof value === "number" && Number.isFinite(value) && value >= 0
  && value <= 1000000 && /^[0-9]+(?:[.][0-9]{1,2})?$/.test(String(value)) ? Math.round(value * 100) : null;
const isRest = slot => slot?.rest === true && /休息|自由|用餐|酒店|住宿/.test(slot.spot || "");
const clone = value => structuredClone(value);
const activityContent = slot => Object.fromEntries(Object.entries(slot).filter(([key,value])=>
  value!==null&&value!==undefined&&!["route","transportation","travelMinutes"].includes(key)));

export function parseJson(text) {
  if (typeof text !== "string") return null;
  const body = text.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  try { return JSON.parse(body); } catch { return null; }
}
export function deliverBaseline(c, text) {
  const raw = parseJson(text);
  if (!raw || raw.success === false || c.kind !== "revise") return { plan: raw, patch: null, patchScopeValid: true };
  const targets = c.revision.slot === "day" ? slots : [c.revision.slot];
  const allowed = new Set([...targets, "budgetBreakdown"]);
  const patchScopeValid = Object.keys(raw).every(key => allowed.has(key)) && targets.every(key => raw[key] && typeof raw[key] === "object");
  const plan = clone(c.basePlan);
  for (const name of targets) if (raw[name]) plan.dailyItinerary[c.revision.day - 1][name] = raw[name];
  if (raw.budgetBreakdown) plan.budgetBreakdown = raw.budgetBreakdown;
  return { plan, patch: raw, patchScopeValid };
}
export function scoreCase(c, observation, frozen) {
  const plan = observation.plan;
  const outputText = String(observation.visibleText || observation.rawText || plan?.error || "");
  const modelText = String(observation.modelFinalText || "");
  const refusalMatched = plan?.success === false
    ? new RegExp(c.expect.refusalPattern || "无法|不能|不足|不可行|预算|无.*方案").test(outputText + " " + String(plan.error || ""))
    : !plan && new RegExp(c.expect.refusalPattern || "无法|不能|不足|不可行").test(outputText);
  const expectedRefusal = c.expectedOutcome === "refused";
  const infrastructureError = !!observation.error && /MODEL_AUTH|MODEL_RATE|MODEL_TIMEOUT|MODEL_CONNECTION|MODEL_UNAVAILABLE|HTTP_(401|402|403|429|5[0-9][0-9])|NETWORK|BACKEND_EXIT/.test(observation.error.code || "");
  const refusal=refusalMatched&&!infrastructureError;
  if (expectedRefusal) return {
    expectedOutcome: c.expectedOutcome, outcome: refusal ? "refused" : plan?.success ? "completed" : infrastructureError ? "infrastructure_error" : "failed",
    taskPassed: refusal, correctRefusal: refusal, safeNoPlan: !plan || plan.success === false,
    modelRefusalObserved: new RegExp(c.expect.refusalPattern).test(modelText),
    checks: { correctRefusal: refusal }, failures: refusal ? [] : ["expected_explicit_refusal"],
    metrics: {}, infrastructureError,
  };
  const checks = {};
  const fail = [];
  function check(name, condition) { checks[name] = !!condition; if (!condition) fail.push(name); }
  const env = frozen.cities[c.input.city];
  const lookup = new Map(env.places.map(p => [p.id, p]));
  const named = new Map();
  for (const p of env.places) named.set(p.name, [...(named.get(p.name) || []), p]);
  const resolve = s => {
    if (s?.placeId) return lookup.get(s.placeId);
    const matches = named.get(String(s?.spot || "").trim()) || [];
    return matches.length === 1 ? matches[0] : null;
  };
  check("deliveredPlan", plan?.success === true);
  check("identity", plan?.city === c.input.city && plan?.days === c.input.days && cents(plan?.totalBudget) === cents(c.input.budget));
  check("travelMode", plan?.travelMode === c.input.travelMode);
  const days = Array.isArray(plan?.dailyItinerary) ? plan.dailyItinerary : [];
  const shape = days.length === c.input.days && days.every((d, i) => d && d.day === i + 1 && slots.every(k => d[k] && typeof d[k].spot === "string" && d[k].spot.trim()));
  check("completeSchedule", shape);
  let actualCount = 0, grounded = 0, requestedLegs = 0, validReportedLegs = 0, availableLegs = 0;
  let durationValid = shape, timeValid = shape, ticketCents = 0, ticketValid = shape, knownFare = 0;
  let routeExists = shape, routeCorrect = shape, noUnderestimate = shape, distinct = shape, perDay = shape;
  const visited = new Set(), categories = new Set(), visitedIds = new Set();
  for (const day of days) {
    let previous = null, minutes = 0, n = 0;
    for (const name of slots) {
      const s = day?.[name];
      if (!s) { durationValid = false; continue; }
      const visit = s.visitMinutes, travel = s.travelMinutes;
      const bounded = Number.isInteger(visit) && visit >= 15 && visit <= 360 && Number.isInteger(travel) && travel >= 0 && travel <= 240;
      if (!bounded) durationValid = false;
      if (Number.isFinite(visit)) minutes += visit;
      const fee = cents(s.estimatedCost);
      if (fee === null) ticketValid = false; else ticketCents += fee;
      if (isRest(s)) {
        if (Number.isFinite(travel)) minutes += travel;
        continue;
      }
      n++; actualCount++;
      const p = resolve(s);
      if (p) { grounded++; categories.add(p.category); visitedIds.add(p.id); }
      const identity = p?.id || String(s.spot || "").replace(/\s/g, "");
      if (visited.has(identity)) distinct = false;
      visited.add(identity);
      if (p && (fee === null || fee < cents(p.ticketBudget))) ticketValid = false;
      if (p && (!Number.isFinite(visit) || visit < p.minVisitMinutes)) durationValid = false;
      if (previous && p) {
        requestedLegs++;
        const expected = env.routes.find(r => r.fromId === previous.id && r.toId === p.id && r.profile === c.input.travelMode);
        const unavailable = c.environment?.noTransit && c.input.travelMode === "transit";
        if (!expected || unavailable) { routeExists = false; minutes += Number(travel || 0); }
        else {
          availableLegs++;
          const actualMinutes = Math.ceil(expected.seconds / 60);
          minutes += Math.max(actualMinutes, Number(travel || 0));
          if (!Number.isInteger(travel) || travel < actualMinutes) noUnderestimate = false;
          knownFare += Math.round(expected.budgetFloor * 100);
          if (s.route) {
            const valid = s.route.fromId === previous.id && s.route.toId === p.id && s.route.profile === c.input.travelMode
              && Math.abs(s.route.seconds - expected.seconds) <= 1 && Math.abs(s.route.meters - expected.meters) <= 1;
            if (valid) validReportedLegs++; else routeCorrect = false;
          }
        }
      } else if (Number.isFinite(travel)) minutes += travel;
      previous = p || null;
    }
    if (n < c.expect.minimumAttractionsPerDay) perDay = false;
    if (minutes > c.expect.maxDailyMinutes) timeValid = false;
  }
  check("groundedPlaces", actualCount > 0 && grounded === actualCount);
  check("distinctPlaces", distinct);
  check("attractionsPerDay", perDay);
  check("visitAndTravelValues", durationValid);
  check("dailyTimeUsingReferenceRoutes", timeValid);
  check("routeAvailable", routeExists);
  check("reportedRouteConsistent", routeCorrect);
  check("travelNotUnderestimated", noUnderestimate);
  check("ticketReferenceCoverage", ticketValid);
  const b = plan?.budgetBreakdown;
  const fields = ["accommodation", "food", "transportation", "tickets", "other"];
  const costs = fields.map(k => cents(b?.[k]));
  const moneyValid = costs.every(v => v !== null);
  check("budgetValid", moneyValid && costs.reduce((a, b) => a + b, 0) <= cents(c.input.budget)
    && costs.reduce((a, b) => a + b, 0) > 0);
  check("ticketSum", moneyValid && cents(b.tickets) === ticketCents);
  check("minimumExpenses", moneyValid && cents(b.accommodation) >= cents(c.expect.minimumAccommodation)
    && cents(b.food) >= cents(c.expect.minimumFood) && cents(b.other) >= cents(c.expect.minimumOther));
  check("transportReferenceCoverage", moneyValid && cents(b.transportation) >= knownFare);
  if (c.expect.requiredCategory) check("requiredCategory", categories.has(c.expect.requiredCategory));
  if (c.expect.requiredPlaceId) check("requiredPlace", visitedIds.has(c.expect.requiredPlaceId));
  if (c.kind === "revise") {
    const targets = c.revision.slot === "day" ? slots : [c.revision.slot];
    let preserved = shape && observation.patchScopeValid !== false;
    for (let i = 0; i < c.basePlan.days; i++) for (const name of slots) {
      if (i === c.revision.day - 1 && targets.includes(name)) continue;
      // Optional absent and explicit-null fields are equivalent after API serialization.
      // Derived route fields may be recalculated; activity content must stay unchanged.
      const original = activityContent(c.basePlan.dailyItinerary[i][name]);
      const next = activityContent(days[i]?.[name] || {});
      if (!isDeepStrictEqual(original, next)) preserved = false;
    }
    if (b) for (const key of ["accommodation", "food", "other"])
      if (cents(b[key]) !== cents(c.basePlan.budgetBreakdown[key])) preserved = false;
    check("revisionScope", preserved);
  }
  return {
    expectedOutcome: c.expectedOutcome, outcome: plan?.success ? "completed" : infrastructureError ? "infrastructure_error" : refusal ? "refused" : "failed",
    taskPassed: Object.values(checks).every(Boolean), correctRefusal: null, checks, failures: fail,
    infrastructureError, metrics: { actualCount, grounded, requestedLegs, availableLegs, validReportedLegs,
      groundingRate: actualCount ? grounded / actualCount : null,
      reportedRouteCoverage: requestedLegs ? validReportedLegs / requestedLegs : null },
  };
}

