// Opt-in real BGE-M3 + Qwen validation. Uses a new local test account, retains its data.
import assert from "node:assert/strict";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import { localClient, testAccount, waitForJob } from "./local-api.mjs";
const address = process.env.RAG_VERIFY_URL || "http://127.0.0.1:3200",
  client = localClient(address),
  account = testAccount();
const source = await readFile("eval/rag-cases.json", "utf8"),
  cases = JSON.parse(source);
const record = {
  at: new Date().toISOString(),
  dataset: cases.version,
  datasetSha256: createHash("sha256").update(source).digest("hex"),
  model: "Qwen/Qwen3.5-35B-A3B",
  embeddingModel: "BAAI/bge-m3",
  cases: [],
  checks: {},
};
await mkdir(".runtime", { recursive: true });
await writeFile(
  ".runtime/rag-eval-account.json",
  JSON.stringify(account) + "\n",
);
const flush = () =>
  writeFile(
    ".runtime/phase5-rag-live.json",
    JSON.stringify(record, null, 2) + "\n",
  );
try {
  await client.register(account);
  const seedResponse = await client.request(
    "/api/knowledge/seed",
    "POST",
    {},
    120000,
  );
  assert.equal(seedResponse.status, 200);
  const seeded = (await seedResponse.json()).data;
  record.seed = seeded;
  assert.equal(seeded.imported, 20);
  assert.equal(seeded.failures.length, 0);
  const currentNote = await client.api("/api/knowledge/documents", "POST", {
    title: "我的参访笔记（虚构验收示例）",
    city: "杭州",
    content:
      "这是一份用户自建的虚构参访笔记，仅用于验证模型无法从训练知识获知的个人资料。雨天集合暗号为青瓷4729，集合地点为梧桐阅读角。请勿把这些虚构安排当成官方旅游信息。",
  });
  const oldNote = await client.api("/api/knowledge/documents", "POST", {
    title: "旧版学习活动资料（虚构验收示例）",
    city: "杭州",
    validFrom: "2025-01-01",
    validTo: "2025-12-31",
    content:
      "这份虚构的旧版学习活动资料只在2025年有效。旧版学习活动集合点为旧书房，活动用于阅读与文化知识交流，不代表任何真实旅游场馆的服务。",
  });
  record.noteIds = [currentNote.document.id, oldNote.document.id];
  record.checks.importAndSeed = true;
  await flush();
  for (const item of cases.cases) {
    const row = { ...item };
    try {
      const conversation = await client.api("/api/conversations", "POST", {});
      row.direct = await client.chat(conversation.id, item.question);
      const begin = performance.now();
      const question = await client.api("/api/knowledge/questions", "POST", {
        city: item.city,
        question: item.question,
        asOf: item.asOf || null,
        topK: 4,
      });
      row.rag = await waitForJob(
        client,
        question,
        125000,
        "/api/knowledge/questions",
      );
      row.durationMs = Math.round(performance.now() - begin);
      assert.equal(
        row.rag.status,
        item.expectedStatus,
        row.rag.message || row.rag.status,
      );
      if (item.expectedStatus === "READY") {
        const text = row.rag.answer.claims.map((c) => c.text).join("\n");
        assert(
          item.terms.every((term) => text.includes(term)),
          "Required factual terms missing",
        );
        for (const claim of row.rag.answer.claims)
          for (const citation of claim.citations) {
            const origin = row.rag.answer.sources.find(
              (s) => s.ref === citation.ref,
            );
            assert(
              origin && origin.content.includes(citation.quote),
              "Quote not in source",
            );
            const detail = await client.api(
              `/api/knowledge/documents/${origin.documentId}`,
            );
            assert(
              detail.chunks.some(
                (p) =>
                  p.id === origin.chunkId && p.content.includes(citation.quote),
              ),
              "Quote not locatable in stored chunk",
            );
          }
      } else
        assert(row.rag.answer.abstained && row.rag.answer.claims.length === 0);
      row.passed = true;
      row.directTermMatches = item.terms.filter((term) =>
        row.direct.answer.includes(term),
      ).length;
    } catch (error) {
      row.passed = false;
      row.error = error.message;
    }
    record.cases.push(row);
    await flush();
    console.log(
      `${row.passed ? "PASS" : "FAIL"} ${item.id} ${item.question}${row.error ? " " + row.error : ""}`,
    );
  }
  const previous = record.cases.find((c) => c.rag?.status === "READY").rag;
  await client.api("/api/auth/logout", "POST", {});
  await client.login(account);
  assert.deepEqual(
    (await client.api(`/api/knowledge/questions/${previous.id}`)).answer,
    previous.answer,
  );
  record.checks.reloginRestoresCitations = true;
  record.summary = {
    total: record.cases.length,
    passed: record.cases.filter((c) => c.passed).length,
    failed: record.cases.filter((c) => !c.passed).length,
  };
  record.passed = record.summary.failed === 0;
  await flush();
  if (!record.passed) process.exitCode = 1;
  console.log(JSON.stringify(record.summary));
} catch (error) {
  record.error = error.message;
  record.passed = false;
  await flush();
  console.error(error.message);
  process.exitCode = 1;
}
