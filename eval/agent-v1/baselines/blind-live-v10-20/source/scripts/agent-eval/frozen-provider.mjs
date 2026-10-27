import { createServer } from "node:http";
import { sharedContext, hash, MARKER, fixturePlan, fixturePatch, SLOTS, clone, wirePayload } from "./protocol.mjs";
import { RequestPacer } from "./pacer.mjs";

export async function startFrozenProvider({ dataset, frozen, mode, settings, pacing }) {
  const pacer=mode==="live"&&pacing?new RequestPacer(pacing):null;
  const cases = new Map(dataset.cases.map(c => [c.id, c]));
  const records = new Map(), contexts = new Map(), mapCalls = new Map();
  const keyFor = (id, group) => id + ":" + group;
  function callsFor(id, group) {
    const key = keyFor(id, group);
    if (!records.has(key)) records.set(key, []);
    return records.get(key);
  }
  const json = (res, status, value) => {
    res.writeHead(status, { "Content-Type": "application/json;charset=utf-8" });
    res.end(JSON.stringify(value));
  };
  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, "http://127.0.0.1");
      if (url.pathname === "/health") return json(res, 200, { ok: true });
      if (url.pathname.startsWith("/v3/")) {
        const id = req.headers["x-travel-eval-case"];
        const c = cases.get(id);
        if (!c) return json(res, 400, { status: "0", infocode: "20000" });
        const data = frozen.cities[c.input.city];
        const list = mapCalls.get(id) || [];
        const event = { path: url.pathname, at: new Date().toISOString() };
        list.push(event); mapCalls.set(id, list);
        if (url.pathname === "/v3/place/text") {
          if (url.searchParams.get("city") !== c.input.city) return json(res, 200, { status: "0", infocode: "20001" });
          return json(res, 200, { status: "1", pois: data.places.map(p => ({
            id: p.id, name: p.name, address: "离线评测固定数据（非实时查询）",
            location: p.longitude.toFixed(6) + "," + p.latitude.toFixed(6),
          })) });
        }
        const profile = url.pathname.endsWith("/walking") ? "walking" : url.pathname.endsWith("/driving") ? "driving" : "transit";
        const byCoordinate = value => data.places.find(p => p.longitude.toFixed(6) + "," + p.latitude.toFixed(6) === value);
        const from = byCoordinate(url.searchParams.get("origin")), to = byCoordinate(url.searchParams.get("destination"));
        event.fromId = from?.id; event.toId = to?.id; event.profile = profile;
        if (!from || !to || profile !== c.input.travelMode) return json(res, 200, { status: "0", infocode: "20001" });
        const route = data.routes.find(r => r.fromId === from.id && r.toId === to.id && r.profile === profile);
        if (profile === "transit") {
          if (url.searchParams.get("city") !== c.input.city) return json(res, 200, { status: "0", infocode: "20001" });
          return json(res, 200, { status: "1", route: { transits: c.environment?.noTransit ? [] : [{
            duration: String(route.seconds), distance: String(route.meters), cost: String(route.fare),
            segments: [{ walking: { distance: "0" }, bus: { buslines: [{
              name: "离线评测公交线", distance: String(route.meters),
              departure_stop: { name: from.name }, arrival_stop: { name: to.name },
            }] } }],
          }] } });
        }
        return json(res, 200, { status: "1", route: { paths: [{
          duration: String(route.seconds), distance: String(route.meters), ...(profile === "driving" ? { tolls: "0" } : {}),
        }] } });
      }
      if (url.pathname !== "/chat/completions") return json(res, 404, { error: "unknown local evaluation path" });
      const parts = []; for await (const part of req) parts.push(part);
      const payload = JSON.parse(Buffer.concat(parts).toString("utf8"));
      const match = JSON.stringify(payload.messages).match(MARKER);
      const c = cases.get(match?.[1]);
      if (!c) return json(res, 400, { error: "missing evaluation case" });
      const group = payload.tools?.length ? "agent" : "one_shot";
      const shared = sharedContext(c, frozen);
      const digest = hash(shared);
      if (contexts.has(c.id) && contexts.get(c.id) !== digest) throw Error("Shared evidence changed within a case");
      contexts.set(c.id, digest);
      const forwarded = wirePayload(payload, shared);
      const record = { at: new Date().toISOString(), contextSha256: digest, request: forwarded,
        clientAttemptId:req.headers["x-travel-model-attempt"]||null,
        recoveryOf:req.headers["x-travel-model-recovery-of"]||null };
      callsFor(c.id, group).push(record);
      const at = performance.now();
      let response;
      if (mode === "fixture") {
        const plan = fixturePlan(c, frozen);
        if (group === "one_shot") response = final(c.kind === "revise" && plan.success ? fixturePatch(c, plan) : plan);
        else if (!plan.success) response = { choices: [{ message: { role: "assistant", content:"",tool_calls:[{
          id:"fixture_refuse",type:"function",function:{name:"report_infeasible",arguments:JSON.stringify({reason:plan.error})}
        }] }, finish_reason: "tool_calls" }] };
        else {
          const results = payload.messages.filter(m => m.role === "tool");
          const steps = [];
          steps.push(["estimate_budget", plan.budgetBreakdown]);
          steps.push(["search_places", { keywords: "景点" }]);
          for (const day of plan.dailyItinerary) {
            let previous = null;
            for (const name of SLOTS) {
              const slot = day[name];
              if (slot.rest) continue;
              if (previous) steps.push(["route_places", { fromPlaceId: previous, toPlaceId: slot.placeId }]);
              previous = slot.placeId;
            }
          }
          steps.push(["validate_itinerary", { draftJson: c.kind === "revise" ? fixturePatch(c, plan) : plan }]);
          const step = steps[results.length];
          response = step ? { choices: [{ message: { role: "assistant", content: "", tool_calls: [{
            id: "fixture_" + results.length, type: "function", function: { name: step[0], arguments: JSON.stringify(step[1]) },
          }] }, finish_reason: "tool_calls" }] } : { choices: [{ message: { role: "assistant", content: "草稿完成" }, finish_reason: "stop" }] };
        }
        response.usage = { prompt_tokens: 100, completion_tokens: 100 };
        record.httpStatus = 200; record.response = response;
        record.durationMs = Math.round(performance.now() - at);
        return json(res, 200, response);
      }
      const abort = new AbortController();
      res.on("close", () => { if (!res.writableEnded) abort.abort(); });
      let permit;
      try {
        if(pacer){
          const promptChars=JSON.stringify({messages:forwarded.messages,tools:forwarded.tools}).length;
          const reserved=Math.ceil(promptChars*0.5)+Number(forwarded.max_tokens||4096);
          permit=await pacer.acquire(reserved,abort.signal);
          record.queueWaitMs=permit.waitMs;
          record.reservedTokens=reserved;
          record.upstreamStartedAt=new Date().toISOString();
        }
        const upstream = await fetch(settings.LLM_BASE_URL.replace(/\/$/, "") + "/chat/completions", {
          method: "POST",
          headers: { Authorization: "Bearer " + settings.LLM_API_KEY, "Content-Type": "application/json" },
          body: JSON.stringify(forwarded),
          signal: AbortSignal.any([abort.signal, AbortSignal.timeout(122000)]),
        });
        record.httpStatus = upstream.status;
        record.upstreamStatus = upstream.status;
        if (!upstream.ok) {
          if(upstream.status===429)pacer?.rateLimited();
          const detail = await upstream.json().catch(() => ({}));
          record.response = { error: { httpStatus: upstream.status, providerCode: typeof detail.code === "number" ? detail.code : null,
            message: "upstream model request rejected" } };
          return json(res, upstream.status, record.response);
        }
        response = await upstream.json();
        permit?.complete(response.usage?.total_tokens);
        record.response = response;
        return json(res, 200, response);
      } catch(error) {
        record.failurePhase=record.upstreamStatus?"upstream_body":"upstream_request";
        record.failureCategory=abort.signal.aborted?"client_aborted":error?.name==="TimeoutError"?"upstream_timeout":"upstream_connection";
        const code=error?.cause?.code;
        record.networkCode=["ECONNRESET","ECONNREFUSED","ETIMEDOUT","UND_ERR_SOCKET","UND_ERR_CONNECT_TIMEOUT"].includes(code)?code:null;
        record.httpStatus = 504; record.response = { error: "upstream model connection or timeout failure" };
        return json(res, 504, record.response);
      } finally { record.durationMs = Math.round(performance.now() - at); }
    } catch {
      if (!res.headersSent) json(res, 500, { error: "local evaluation relay failure" });
      else res.end();
    }
  });
  // Local server lifetime and per-request deadlines bound resources; avoid racing the Java idle pool at 5s.
  server.keepAliveTimeout=0;
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  return {
    origin: "http://127.0.0.1:" + server.address().port,
    records(id, group) { return clone(callsFor(id, group)); },
    mapCalls(id) { return clone(mapCalls.get(id) || []); },
    contextSha(id) { return contexts.get(id); },
    async close() { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); },
  };
}
function final(plan) {
  return { choices: [{ message: { role: "assistant", content: JSON.stringify(plan) }, finish_reason: "stop" }] };
}

