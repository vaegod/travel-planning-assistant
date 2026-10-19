// Verify TLS using NODE_EXTRA_CA_CERTS pointing at this deployment's public CA.
import assert from "node:assert/strict";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { localClient, testAccount, waitForJob } from "./local-api.mjs";
const root = process.env.DEPLOY_URL || "https://localhost:8443";
assert(
  new URL(root).protocol === "https:",
  "Deployment verification requires HTTPS",
);
const restore = process.argv.includes("--restore"),
  knowledgeEnabled = process.argv.includes("--knowledge-extension"),
  reportFile = process.env.DEPLOY_REPORT || ".runtime/deployment-smoke.json",
  accountFile = `.runtime/deployment-${new URL(root).port || 443}-account.json`;
const client = localClient(root),
  account = restore
    ? JSON.parse(await readFile(accountFile, "utf8"))
    : testAccount();
const record = restore
  ? JSON.parse(await readFile(reportFile, "utf8"))
  : {
      at: new Date().toISOString(),
      origin: root,
      kind: process.env.DEPLOY_KIND || "fixture-integration",
      checks: {},
    };
await mkdir(".runtime", { recursive: true });
if (!restore) await writeFile(accountFile, JSON.stringify(account) + "\n");
try {
  if (restore) {
    await client.login(account);
    if (!knowledgeEnabled) {
      assert.equal((await client.reply("/api/knowledge/documents")).status, 404);
      record.checks.knowledgeDisabledAfterRestart = true;
    }
    const messages = await client.api(
      `/api/conversations/${record.conversationId}/messages`,
    );
    assert.equal(messages.items.length, 2);
    assert(messages.items.every((m) => m.status === "completed"));
    assert.equal(messages.items[1].content, record.reply.answer);
    if (record.tripId)
      assert.equal(
        (await client.api(`/api/trips/${record.tripId}`)).trip.currentVersion,
        1,
      );
    if (knowledgeEnabled && record.ragQuestionId) {
      assert.equal(
        (await client.api(`/api/knowledge/questions/${record.ragQuestionId}`))
          .status,
        "READY",
      );
      assert(
        (await client.api(`/api/knowledge/documents/${record.documentId}`))
          .chunks.length > 0,
      );
      record.checks.ragAfterRestart = true;
    }
    record.checks.persistAfterRestart = true;
    record.restoredAt = new Date().toISOString();
    record.passed = true;
  } else {
    const page = await fetch(root);
    assert.equal(page.status, 200);
    assert((await page.text()).includes('<div id="app">'));
    record.checks.staticPage = true;
    const route = await fetch(root + "/trips");
    assert.equal(route.status, 200);
    assert((await route.text()).includes('<div id="app">'));
    record.checks.spaRoute = true;
    const health = await fetch(root + "/api/health");
    assert.equal(health.status, 200);
    assert.equal((await health.json()).status, "UP");
    record.checks.databaseReadiness = true;
    const csrf = await client.request("/api/auth/csrf");
    assert(
      csrf.headers
        .getSetCookie()
        .some(
          (c) =>
            /HttpOnly/i.test(c) && /Secure/i.test(c) && /SameSite=Lax/i.test(c),
        ),
    );
    await csrf.json();
    record.checks.secureCookie = true;
    assert.equal((await client.reply("/api/trips")).status, 401);
    await client.register(account);
    record.checks.loginAndCsrf = true;
    if (!knowledgeEnabled) {
      assert.equal((await client.reply("/api/knowledge/documents")).status, 404);
      record.checks.knowledgeDisabled = true;
    }
    const conv = await client.api("/api/conversations", "POST", {});
    const reply = await client.chat(
      conv.id,
      "我计划去杭州玩3天，预算2000元，请记住这些条件并简短回复。",
    );
    assert(reply.answer.length > 0 && reply.chunks > 0);
    record.reply = reply;
    record.checks.sseThroughProxy = true;
    if (record.kind === "fixture-integration") {
      const plan = await client.api("/api/travel/recommend", "POST", {
        city: "杭州",
        days: 1,
        budget: 1000,
      });
      assert.equal(plan.success, true);
      assert(!plan.evidence);
      assert.equal(
        (
          await client.reply("/api/planning/jobs", "POST", {
            city: "杭州",
            days: 1,
            budget: 1000,
          })
        ).status,
        410,
      );
      const trip = await client.api("/api/trips", "POST", { plan });
      record.tripId = trip.trip.id;
      record.checks.planAndSave = true;
      record.checks.defaultDirectPlanning = true;
      record.checks.externalWorkflowDisabled = true;
    }
    const messages = await client.api(`/api/conversations/${conv.id}/messages`);
    assert.equal(messages.items.length, 2);
    assert(messages.items.every((m) => m.status === "completed"));
    record.checks.messagesSaved = true;
    // Independent opt-in extension check; default deployment never imports knowledge.
    if (knowledgeEnabled) {
      const imported = await client.api("/api/knowledge/documents", "POST", {
        title: "部署验收茶文化笔记",
        city: "杭州",
        content:
          "这是部署验收用的茶文化参访笔记。双峰馆区位于龙井路88号，龙井馆区位于翁家山268号。本文仅供检索引用功能测试，不代表实时开放信息。",
      });
      record.documentId = imported.document.id;
      const rag = await waitForJob(
        client,
        await client.api("/api/knowledge/questions", "POST", {
          city: "杭州",
          question: "根据资料，茶文化参访笔记中双峰馆区的地址是什么？",
        }),
        125000,
        "/api/knowledge/questions",
      );
      assert.equal(rag.status, "READY", rag.message || rag.status);
      assert(rag.answer.claims.length > 0);
      for (const claim of rag.answer.claims)
        for (const citation of claim.citations) {
          const source = rag.answer.sources.find((s) => s.ref === citation.ref);
          assert(source && source.content.includes(citation.quote));
        }
      record.ragQuestionId = rag.id;
      record.checks.knowledgeImportAndCitations = true;
      assert(
        (await client.api("/api/knowledge/documents")).some(
          (d) => d.id === record.documentId,
        ),
      );
      assert(
        (await client.api("/api/knowledge/questions")).some(
          (q) => q.id === rag.id,
        ),
      );
      record.checks.knowledgeAndAnswerLists = true;
    }
    record.conversationId = conv.id;
    record.passed = true;
  }
} catch (error) {
  record.passed = false;
  record.error = error.message;
  process.exitCode = 1;
}
await writeFile(reportFile, JSON.stringify(record, null, 2) + "\n");
console.log(JSON.stringify(record, null, 2));
