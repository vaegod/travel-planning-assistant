import test from "node:test";
import assert from "node:assert/strict";
import { decodeKnowledgeFile } from "../src/utils/knowledgeFile.ts";
const encode = (text) => new TextEncoder().encode(text);
test("knowledge import decodes Chinese UTF-8 and normalizes BOM/CRLF", () =>
  assert.equal(
    decodeKnowledgeFile("资料.md", encode("\ufeff杭州\r\n文化\r资料")),
    "杭州\n文化\n资料",
  ));
test("knowledge file import rejects PDF even when its bytes look textual", () =>
  assert.throws(() => decodeKnowledgeFile("资料.pdf", encode("text")), /TXT/));
test("knowledge file byte limit applies before decoding", () =>
  assert.throws(
    () => decodeKnowledgeFile("a.txt", new Uint8Array(100001)),
    /100KB/,
  ));
test("knowledge file rejects invalid UTF-8 instead of replacing bytes silently", () =>
  assert.throws(
    () => decodeKnowledgeFile("a.txt", new Uint8Array([0xc3, 0x28])),
    /UTF-8/,
  ));
test("knowledge character limit is distinct from UTF-8 byte length", () => {
  assert.equal(
    decodeKnowledgeFile("a.txt", encode("杭".repeat(12000))).length,
    12000,
  );
  assert.throws(
    () => decodeKnowledgeFile("a.txt", encode("杭".repeat(12001))),
    /12000/,
  );
});
test("knowledge file rejects binary controls but allows line breaks and tabs", () => {
  assert.throws(
    () => decodeKnowledgeFile("a.txt", encode("杭州\u0000资料")),
    /控制字符/,
  );
  assert.equal(
    decodeKnowledgeFile("a.txt", encode("杭州\t资料\n")),
    "杭州\t资料\n",
  );
});
