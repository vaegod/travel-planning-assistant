import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
const places = JSON.parse(
  await readFile(
    new URL("../eval/fixtures/places.json", import.meta.url),
    "utf8",
  ),
);

export async function startProvider({
  port = 0,
  host = "127.0.0.1",
  live = null,
} = {}) {
  const state = { mode: "ok", calls: { places: 0, route: 0, model: 0 } };
  const send = (res, status, value) => {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(value));
  };
  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, "http://localhost");
      if (url.pathname === "/health") {
        return send(res, 200, { status: "ok" });
      }
      if (url.pathname === "/places") {
        state.calls.places++;
        return send(
          res,
          state.mode === "places-fail" ? 503 : 200,
          state.mode === "places-empty" ? { elements: [] } : places,
        );
      }
      if (url.pathname.startsWith("/route/")) {
        state.calls.route++;
        if (state.mode === "no-route")
          return send(res, 200, { code: "NoRoute" });
        const count = url.pathname.split("/").at(-1).split(";").length;
        return send(res, 200, {
          code: "Ok",
          routes: [
            {
              legs: Array.from({ length: count - 1 }, () => ({
                distance: 500,
                duration: state.mode === "long-route" ? 40000 : 300,
              })),
            },
          ],
        });
      }
      if (url.pathname === "/embeddings") {
        const chunks = [];
        for await (const chunk of req) chunks.push(chunk);
        const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
        const values = Array.isArray(payload.input)
          ? payload.input
          : [payload.input];
        return send(res, 200, {
          data: values.map((text, index) => ({
            index,
            embedding: Array.from({ length: 4 }, (_, i) =>
              i ===
              (text.includes("茶")
                ? 0
                : text.includes("运河")
                  ? 1
                  : text.includes("良渚")
                    ? 2
                    : 3)
                ? 1
                : 0,
            ),
          })),
        });
      }
      if (url.pathname !== "/chat/completions")
        return send(res, 404, { error: "unknown fixture path" });
      state.calls.model++;
      const buffers = [];
      for await (const chunk of req) buffers.push(chunk);
      const payload = JSON.parse(Buffer.concat(buffers).toString("utf8"));
      if (state.mode === "model-auth" || state.mode === "model-rate")
        return send(res, state.mode === "model-auth" ? 401 : 429, {
          error: "fixture failure",
        });
      if (live) {
        const abort = new AbortController();
        res.on("close", () => {
          if (!res.writableEnded) abort.abort();
        });
        const response = await fetch(
          live.baseUrl.replace(/\/$/, "") + "/chat/completions",
          {
            method: "POST",
            headers: {
              Authorization: `Bearer ${live.key}`,
              "Content-Type": "application/json",
              Accept: payload.stream ? "text/event-stream" : "application/json",
            },
            body: JSON.stringify(payload),
            signal: AbortSignal.any([
              abort.signal,
              AbortSignal.timeout(125000),
            ]),
          },
        );
        res.writeHead(response.status, {
          "Content-Type":
            response.headers.get("content-type") || "application/json",
        });
        for await (const chunk of response.body) {
          if (res.destroyed) break;
          res.write(chunk);
        }
        res.end();
        return;
      }
      if (!payload.stream) {
        if (payload.messages.some((m) => m.content.includes("目的地城市："))) {
          const prompt = payload.messages.map((m) => m.content).join("\n");
          const city =
            prompt.match(/目的地城市：([^\n]+)/)?.[1]?.trim() || "杭州";
          const days = Number(prompt.match(/旅行天数：(\d+)/)?.[1] || 1);
          const budget = Number(prompt.match(/预算：([\d.]+)/)?.[1] || 1000);
          const slot = {
            spot: "模型参考景点",
            duration: "1小时",
            ticket: "参考估算",
            transportation: "模型交通建议",
            description: "本地替身，仅测试结构，不代表真实旅游资料",
          };
          return send(res, 200, {
            choices: [
              {
                message: {
                  content: JSON.stringify({
                    success: true,
                    city,
                    days,
                    totalBudget: budget,
                    dailyItinerary: Array.from({ length: days }, (_, i) => ({
                      day: i + 1,
                      morning: slot,
                      afternoon: slot,
                      evening: slot,
                    })),
                    budgetBreakdown: {
                      accommodation: 0,
                      food: 80,
                      transportation: 20,
                      tickets: 0,
                      other: 20,
                    },
                    tips: ["以官方信息为准"],
                    warnings: ["模型建议，未查询地图"],
                  }),
                },
                finish_reason: "stop",
              },
            ],
          });
        }
        if (payload.messages[1].content.includes("检索资料：")) {
          const prompt = payload.messages[1].content.split("检索资料：")[1],
            sources = JSON.parse(prompt.slice(0, prompt.lastIndexOf("]") + 1)),
            source = sources[0];
          return send(res, 200, {
            choices: [
              {
                message: {
                  content: JSON.stringify({
                    abstained: false,
                    claims: [
                      {
                        text: source.content.slice(0, 70),
                        citations: [
                          {
                            ref: source.ref,
                            quote: source.content.slice(0, 70),
                          },
                        ],
                      },
                    ],
                  }),
                },
                finish_reason: "stop",
              },
            ],
          });
        }
        const prompt = payload.messages[1].content,
          count = Number(prompt.match(/请选择(\d+)个/)?.[1] || 3),
          text = prompt.split("候选：")[1];
        const candidates = JSON.parse(text.slice(0, text.lastIndexOf("]") + 1));
        let ids = candidates.slice(0, count).map((p) => p.id);
        if (state.mode === "unknown-id") ids[0] = "node/999999999999";
        if (state.mode === "duplicate-id") ids[1] = ids[0];
        if (state.mode === "wrong-count") ids = ids.slice(1);
        return send(res, 200, {
          choices: [
            {
              message: {
                content:
                  state.mode === "bad-json"
                    ? "not-json"
                    : JSON.stringify({ placeIds: ids }),
              },
              finish_reason: state.mode === "truncated" ? "length" : "stop",
            },
          ],
        });
      }
      const system = payload.messages[0].content,
        context = JSON.parse(
          system.match(/会话约束：(\{[^\n]*\})/)?.[1] || "{}",
        ),
        history = payload.messages.map((m) => m.content).join("\n");
      // Read conversation history like the current direct-chat provider contract.
      const city = context.city || (history.includes("杭州") ? "杭州" : "");
      const budget = history.includes("预算改成1500元") ? 1500 : context.budget || 2000;
      const answer = !city
        ? "请告诉我目的地城市、天数和预算。"
        : `${history.includes("小岚") ? "同行人小岚。" : "你还没有告诉我同行人姓名。"}${city}${context.days || 3}天，预算${budget}元；不开车，公交步行，喜欢博物馆。需要重新规划可回首页生成。`;
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      for (const chunk of answer.match(/.{1,8}/gu) || [])
        res.write(
          "data: " +
            JSON.stringify({ choices: [{ delta: { content: chunk } }] }) +
            "\n\n",
        );
      res.write(
        "data: " +
          JSON.stringify({ choices: [{ delta: {}, finish_reason: "stop" }] }) +
          "\n\n",
      );
      res.end("data: [DONE]\n\n");
    } catch (error) {
      if (!res.headersSent)
        send(res, 502, { error: "fixture or upstream request failed" });
      else res.destroy();
    }
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, host, resolve);
  });
  return {
    state,
    port: server.address().port,
    close: () =>
      new Promise((resolve) => {
        server.closeAllConnections();
        server.close(resolve);
      }),
  };
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const fixture = await startProvider({
    port: Number(process.env.PORT || 9000),
    host: "0.0.0.0",
  });
  console.log(
    `Fixture provider listening on ${fixture.port}; no real model or map calls`,
  );
  process.on("SIGTERM", () => fixture.close().then(() => process.exit(0)));
}
