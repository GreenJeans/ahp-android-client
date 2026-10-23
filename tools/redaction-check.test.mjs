import assert from "node:assert/strict";
import { test } from "node:test";
import { findLeaks } from "./redaction-check.mjs";

const out = (body) => ({ "fixture.json": JSON.stringify(body) });
const base = { labels: new Set(["completed", "markdown", "chat/toolCallStart"]), extraWords: ["lorem", "ipsum", "dolor", "amet", "file", "work", "repo"], username: "jdoe" };

test("clean placeholder output passes", () => {
  const outputs = out({ kind: "markdown", status: "completed", content: "Lorem ipsum dolor amet\nlorem 12 ipsum" });
  assert.deepEqual(findLeaks({ ...base, outputs, originals: ["A real sentence about Kubernetes upgrades"] }), []);
});

test("an original string copied into the output is caught", () => {
  const secret = "a production hostname.example";
  const outputs = out({ content: secret });
  assert.ok(findLeaks({ ...base, outputs, originals: [secret] }).some((p) => p.includes("equal to an original")));
});

test("a long original run inside a larger output string is caught", () => {
  const original = "connection string for the payroll database is stored elsewhere";
  const outputs = out({ content: `lorem ${original.slice(10, 40)} ipsum` });
  assert.ok(findLeaks({ ...base, outputs, originals: [original] }).some((p) => p.includes("character run")));
});

test("a word outside the placeholder vocabulary is caught even when no original is known", () => {
  const outputs = out({ content: "lorem Kubernetes ipsum" });
  assert.ok(findLeaks({ ...base, outputs, originals: [] }).some((p) => p.includes("outside the placeholder vocabulary")));
});

test("a long original run made only of structural words is boilerplate, not a leak", () => {
  const original = '{"explanation":"a private reason","isBackground":false}';
  const outputs = out({ content: '{"explanation":"lorem ipsum","isBackground":false}' });
  assert.deepEqual(findLeaks({ ...base, outputs, originals: [original], extraWords: [...base.extraWords, "explanation", "background", "false"] }), []);
});

test("a long original run containing placeholder words is still a leak", () => {
  const original = "lorem ipsum dolor consectetur adipiscing elit sed";
  const outputs = out({ content: `x ${original} y` });
  const problems = findLeaks({ labels: new Set(), outputs, originals: [original], placeholderWords: ["lorem", "ipsum", "dolor", "consectetur", "adipiscing", "elit", "sed", "x"] });
  assert.ok(problems.some((p) => p.includes("character run")), problems.join("; "));
});

test("a camelCase identifier is split, so one unknown part is still caught", () => {
  assert.ok(findLeaks({ ...base, outputs: out({ content: "lorem getPayrollData" }), originals: [] }).length > 0);
});

test("words from output keys and labels are allowed", () => {
  const outputs = out({ invocationMessage: "completed", toolCallStart: "chat/toolCallStart" });
  assert.deepEqual(findLeaks({ ...base, outputs, originals: [] }), []);
});

test("the OS user name, drive paths, home paths, encoded drives and real uuids are caught", () => {
  for (const text of ["lorem jdoe ipsum", "C:\\work\\repo", "/Users/x/work", "/home/x/work", "file:///c%3A/work", "lorem 3f2504e0-4f89-41d3-9a0c-0305e82c3301"]) {
    assert.notDeepEqual(findLeaks({ ...base, outputs: out({ content: text }), originals: [] }), [], text);
  }
});

test("fake uuids and token-shaped strings", () => {
  assert.deepEqual(findLeaks({ ...base, outputs: out({ content: "lorem 00000000-0000-4000-8000-000000000001" }), originals: [] }), []);
  assert.ok(findLeaks({ ...base, outputs: out({ content: "ghp_abcdefghijklmnopqrstuvwxyz0123" }), originals: [] }).length > 0);
});

test("generated constants listed as safe may appear verbatim", () => {
  const png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB";
  assert.deepEqual(findLeaks({ ...base, outputs: out({ data: png }), originals: [png], safeStrings: new Set([png]) }), []);
  assert.ok(findLeaks({ ...base, outputs: out({ data: png }), originals: [png] }).length > 0);
});

test("a leak report never repeats the leaked text", () => {
  const secret = "hunter2-is-the-password-for-prod";
  const problems = findLeaks({ ...base, outputs: out({ content: secret }), originals: [secret] });
  assert.ok(problems.length > 0);
  assert.ok(problems.every((p) => !p.includes("hunter2")));
});
