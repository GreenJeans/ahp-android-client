// Builds redacted chat fixtures from a local probe capture (captures/chat-session.jsonl, made with `--chat`).
//
//   node tools/redact-chat.mjs captures/chat-session.jsonl [outDir]
//
// What it writes (default outDir core-ahp/src/test/resources/live):
//   chat-subscribe-result.json  a subscribe result holding a small ChatState: a few real-shaped turns chosen to cover
//                               every part kind and tool-result content type in the capture, plus the active turn
//   chat-actions.json           the chat actions that streamed in after the snapshot, as action envelopes
//
// Built from a WHITELIST: every field is copied or rebuilt by name, so a field added by a future protocol version
// is dropped, and a part kind or content type this script does not know stops the run instead of passing through.
// Free text (messages, reasoning, tool output, tool input) is masked, never copied: every run of letters becomes a
// placeholder word, short numbers and the punctuation, line breaks, markdown and code-fence structure stay. Ids, paths,
// names and times are replaced; `_meta` is dropped entirely (it carries paths, branch names and quota data).
// tools/redaction-check.mjs then compares the output with the capture and nothing is written if anything leaked.
// The raw capture stays local: captures/ is gitignored.

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { userInfo } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { findLeaks } from "./redaction-check.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const input = process.argv[2];
const outDir = process.argv[3] ? resolve(process.argv[3]) : resolve(here, "..", "core-ahp", "src", "test", "resources", "live");
if (!input) {
  console.error("usage: node tools/redact-chat.mjs <chat-capture.jsonl> [outDir]");
  process.exit(2);
}
const fail = (message) => {
  console.error(message);
  process.exit(1);
};

const MAX_TURNS = 8;
const MAX_FILE_BYTES = 250_000;
const BASE_TIME = Date.parse("2026-10-20T09:00:00.000Z");
const iso = (minutes) => new Date(BASE_TIME + minutes * 60_000).toISOString();
const FAKE_CHAT = "ahp-chat:/00000000-0000-4000-8000-000000000c01";
const MODEL = "model-a";
const PNG_1X1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
const LOREM_BASE64 = Buffer.from("lorem ipsum").toString("base64");

const LOREM = ("lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua " +
  "enim minim veniam quis nostrud exercitation ullamco laboris nisi aliquip commodo consequat duis aute irure reprehenderit voluptate " +
  "velit esse cillum fugiat nulla pariatur excepteur sint occaecat cupidatat proident sunt culpa officia deserunt mollit anim est laborum").split(" ");
const KEEP_LANGUAGES = new Set(["powershell", "pwsh", "bash", "sh", "json", "yaml", "xml", "diff", "text", "markdown", "md", "kotlin", "java", "python", "ts", "typescript", "js", "javascript", "cs", "csharp", "sql", "html", "css"]);
const KEEP_INPUT_KEYS = new Set(["command", "explanation", "goal", "isBackground", "timeout", "filePath", "path", "query", "pattern", "content", "input", "url", "description", "prompt", "oldString", "newString", "includePattern", "isRegexp", "startLine", "endLine", "name"]);
const FAKE_WORDS = ["file", "work", "repo", "chat", "terminal", "blob", "content", "session", "tool", "call", "part", "turn", "agent", "model", "mcp", "server", "example", "invalid", "https", "http", "args", "arg", "cursor", "src"];

// ---- bookkeeping for the leak check ---------------------------------------------------------------------------
const originals = new Set();
const labels = new Set();
const seen = (s) => {
  if (typeof s === "string") originals.add(s);
  return s;
};
const label = (value) => {
  if (typeof value !== "string" || !/^[A-Za-z][A-Za-z0-9_./:-]{0,39}$/.test(value)) fail(`a value that should be a short protocol label is not one (${typeof value}, ${String(value).length} chars)`);
  labels.add(value);
  return value;
};
const idMaps = new Map();
const fakeId = (prefix, real) => {
  seen(real);
  const map = idMaps.get(prefix) ?? new Map();
  idMaps.set(prefix, map);
  if (!map.has(real)) map.set(real, `${prefix}-${map.size + 1}`);
  return map.get(real);
};
const fakeUuidUri = (scheme, letter, real) => {
  const n = Number(fakeId(`uri-${scheme}`, real).split("-").pop());
  return `${scheme}:/00000000-0000-4000-8000-${letter}${String(n).padStart(11, "0")}`;
};
const fakeFileUri = (real) => `file:///work/repo-a/src/${fakeId("file", real)}.txt`;

// ---- masking free text ----------------------------------------------------------------------------------------
const fnv = (s) => {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 0x01000193) >>> 0;
  return h;
};
const placeholderWord = (word) => {
  const w = LOREM[fnv(word.toLowerCase()) % LOREM.length];
  if (word.length > 1 && word === word.toUpperCase()) return w.toUpperCase();
  return word[0] !== word[0].toLowerCase() ? w[0].toUpperCase() + w.slice(1) : w;
};
function mask(text, max = 300) {
  if (typeof text !== "string") fail(`expected a string to mask, got ${typeof text}`);
  seen(text);
  let t = text;
  if (t.length > max) {
    t = t.slice(0, max);
    const cut = t.lastIndexOf("\n");
    if (cut > max / 2) t = t.slice(0, cut);
  }
  return t.replace(/```[A-Za-z0-9+#-]*|\p{L}+|\d+/gu, (m) => {
    if (m.startsWith("```")) {
      const lang = m.slice(3);
      return "```" + (KEEP_LANGUAGES.has(lang.toLowerCase()) ? lang : "");
    }
    if (/^\d+$/.test(m)) return m.length <= 2 ? m : "7".repeat(m.length);
    return placeholderWord(m);
  });
}

// ---- the capture ----------------------------------------------------------------------------------------------
const frames = readFileSync(input, "utf8").split("\n").filter(Boolean).map((line) => JSON.parse(line)).filter((row) => row.dir === "in").map((row) => JSON.parse(row.frame));
const snapshot = frames.map((f) => f.result?.snapshot).find((s) => typeof s?.resource === "string" && s.resource.startsWith("ahp-chat"));
if (!snapshot) fail("capture has no chat snapshot");
const realActions = frames
  .filter((f) => f.method === "action" && f.params?.channel === snapshot.resource && String(f.params.action?.type).startsWith("chat/"))
  .map((f) => f.params);
const state = snapshot.state;

// ---- choosing turns -------------------------------------------------------------------------------------------
function partTokens(p) {
  const t = new Set([`part:${p.kind}`]);
  if (p.kind === "systemNotification" && p._meta) t.add("part:systemNotification:meta");
  if (p.kind !== "toolCall") return t;
  const tc = p.toolCall;
  t.add(`tool:status:${tc.status}`);
  t.add(`tool:confirmed:${tc.confirmed}`);
  t.add(`tool:success:${tc.success}`);
  t.add(`tool:invocation:${typeof tc.invocationMessage === "string" ? "string" : "markdown"}`);
  t.add(`tool:past:${typeof tc.pastTenseMessage === "string" ? "string" : "markdown"}`);
  if (tc.error) t.add("tool:error");
  if (tc.contributor) t.add(`tool:contributor:${tc.contributor.kind}`);
  if (tc.structuredContent) t.add("tool:structured");
  if (tc._meta?.toolKind) t.add(`tool:kind:${tc._meta.toolKind}`);
  if (!tc.content?.length) t.add("tool:no-content");
  for (const c of tc.content ?? []) {
    t.add(`content:${c.type}`);
    if (c.type === "terminal") {
      t.add(c.isPty ? "terminal:pty" : "terminal:not-pty");
      if (c.result?.truncated) t.add("terminal:truncated");
      if (c.result && c.result.exitCode !== 0) t.add("terminal:nonzero-exit");
    }
    if (c.type === "embeddedResource") t.add(`embedded:${c.contentType}`);
  }
  return t;
}
const turnTokens = (turn) => {
  const t = new Set([`turn:state:${turn.state}`]);
  if (turn.message?.agent) t.add("turn:agent");
  for (const p of turn.responseParts) partTokens(p).forEach((k) => t.add(k));
  return t;
};

function pickTurns(turns) {
  const candidates = turns.map((turn, index) => ({ turn, index, tokens: turnTokens(turn), size: JSON.stringify(turn).length }));
  const covered = new Set();
  const chosen = [];
  while (chosen.length < MAX_TURNS) {
    let best = null;
    let bestGain = 0;
    for (const c of candidates) {
      if (chosen.includes(c)) continue;
      const gain = [...c.tokens].filter((k) => !covered.has(k)).length;
      if (gain > bestGain || (gain === bestGain && gain > 0 && c.size < best.size)) {
        best = c;
        bestGain = gain;
      }
    }
    if (!best) break;
    chosen.push(best);
    best.tokens.forEach((k) => covered.add(k));
  }
  chosen.sort((a, b) => a.index - b.index);
  const kept = new Set();
  const trimmed = chosen.map(({ turn }) => ({
    ...turn,
    responseParts: turn.responseParts.filter((p, i, all) => {
      const tokens = partTokens(p);
      const novel = [...tokens].some((k) => !kept.has(k));
      tokens.forEach((k) => kept.add(k));
      return novel || i < 2 || i === all.length - 1;
    }),
  }));
  return { turns: trimmed, covered: [...covered].sort(), from: turns.length };
}

// ---- rebuilding objects by whitelist --------------------------------------------------------------------------
const only = (object, allowed, where) => {
  const extra = Object.keys(object).filter((k) => !allowed.includes(k));
  if (extra.length) dropped.add(`${where}: ${extra.join(", ")}`);
  return object;
};
const dropped = new Set();

const message = (v) => {
  if (typeof v === "string") return mask(v, 160);
  only(v, ["markdown"], "message");
  return { markdown: mask(v.markdown, 240) };
};

function toolInput(raw) {
  seen(raw);
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return mask(raw, 200);
  }
  let n = 0;
  const walk = (v) => {
    if (typeof v === "string") return mask(v, 160);
    if (Array.isArray(v)) return v.slice(0, 4).map(walk);
    if (v && typeof v === "object") {
      return Object.fromEntries(Object.entries(v).slice(0, 6).map(([k, x]) => [KEEP_INPUT_KEYS.has(k) ? k : `arg${++n}`, walk(x)]));
    }
    return v;
  };
  return JSON.stringify(walk(parsed));
}

const usage = (u) => {
  only(u, ["inputTokens", "outputTokens", "cacheReadTokens", "model", "_meta"], "usage");
  const out = {};
  for (const k of ["inputTokens", "outputTokens", "cacheReadTokens"]) if (typeof u[k] === "number") out[k] = u[k];
  if (u.model) {
    seen(u.model);
    out.model = MODEL;
  }
  return out;
};

const CONTENT_FIELDS = {
  text: ["type", "text"],
  terminal: ["type", "resource", "title", "isPty", "result"],
  fileEdit: ["type", "before", "after", "diff", "_vscodeEditAttribution"],
  embeddedResource: ["type", "contentType", "data"],
  subagent: ["type", "agentName", "description", "resource", "title"],
};

function content(c) {
  only(c, CONTENT_FIELDS[c.type] ?? [], `content ${c.type}`);
  switch (c.type) {
    case "text":
      return { type: label("text"), text: mask(c.text, 400) };
    case "terminal": {
      const out = { type: label("terminal"), resource: fakeUuidUri("ahp-terminal", "b", c.resource), title: mask(c.title, 40), isPty: c.isPty };
      if (c.result) {
        out.result = { exitCode: c.result.exitCode, preview: mask(c.result.preview, 400) };
        if (c.result.truncated !== undefined) out.result.truncated = c.result.truncated;
      }
      return out;
    }
    case "fileEdit": {
      const ref = (r) => ({ uri: fakeFileUri(r.uri), content: { uri: `ahp-content:/${fakeId("blob", r.content.uri)}` } });
      const out = { type: label("fileEdit") };
      if (c.before) out.before = ref(c.before);
      if (c.after) out.after = ref(c.after);
      if (c.diff) out.diff = { added: c.diff.added, removed: c.diff.removed };
      return out;
    }
    case "embeddedResource": {
      const contentType = label(c.contentType);
      seen(c.data);
      return { type: label("embeddedResource"), contentType, data: contentType === "image/png" ? PNG_1X1 : LOREM_BASE64 };
    }
    case "subagent": {
      const out = { type: label("subagent"), agentName: fakeId("agent", c.agentName), description: mask(c.description, 80), title: mask(c.title, 40) };
      if (c.resource) out.resource = fakeUuidUri("ahp-session", "a", c.resource);
      return out;
    }
    default:
      return fail(`unhandled tool-result content type "${c.type}": extend tools/redact-chat.mjs (whitelist) before fixtures can be built`);
  }
}

function toolMeta(m) {
  if (!m) return undefined;
  const out = {};
  if (m.toolKind) out.toolKind = label(m.toolKind);
  if (m.language) out.language = label(m.language);
  if (m.mcpServerName) out.mcpServerName = fakeId("mcp-server", m.mcpServerName);
  if (m.mcpToolName) out.mcpToolName = fakeId("mcp-tool", m.mcpToolName);
  if (m.subagentAgentName) out.subagentAgentName = fakeId("agent", m.subagentAgentName);
  if (m.subagentDescription) out.subagentDescription = mask(m.subagentDescription, 80);
  return Object.keys(out).length ? out : undefined;
}

function toolCall(tc) {
  only(tc, ["_meta", "confirmed", "content", "contributor", "displayName", "error", "intention", "invocationMessage", "pastTenseMessage", "status", "structuredContent", "success", "toolCallId", "toolInput", "toolName"], "toolCall");
  const out = {
    toolCallId: fakeId("call", tc.toolCallId),
    toolName: fakeId("tool", tc.toolName),
    displayName: mask(tc.displayName, 60),
    status: label(tc.status),
    confirmed: label(tc.confirmed),
    success: tc.success,
  };
  if (tc.intention !== undefined) out.intention = mask(tc.intention, 200);
  out.invocationMessage = message(tc.invocationMessage);
  out.pastTenseMessage = message(tc.pastTenseMessage);
  out.toolInput = toolInput(tc.toolInput);
  if (tc.content) out.content = tc.content.map(content);
  if (tc.error) out.error = { code: label(tc.error.code), message: mask(tc.error.message, 200) };
  if (tc.contributor) {
    const c = { kind: label(tc.contributor.kind) };
    if (tc.contributor.clientId) c.clientId = fakeId("client", tc.contributor.clientId);
    if (tc.contributor.customizationId) c.customizationId = fakeId("customization", tc.contributor.customizationId);
    out.contributor = c;
  }
  if (tc.structuredContent) {
    only(tc.structuredContent, ["results"], "structuredContent");
    out.structuredContent = {
      results: tc.structuredContent.results.slice(0, 2).map((r) => ({ content: mask(r.content, 300), contentUrl: `https://example.invalid/${fakeId("doc", r.contentUrl)}`, title: mask(r.title, 60) })),
    };
  }
  const meta = toolMeta(tc._meta);
  if (meta) out._meta = meta;
  return out;
}

function part(p) {
  only(p, ["kind", "id", "content", "toolCall", "_meta"], `part ${p.kind}`);
  switch (p.kind) {
    case "markdown":
    case "reasoning":
      return { kind: label(p.kind), id: fakeId("part", p.id), content: mask(p.content, 500) };
    case "systemNotification": {
      const out = { kind: label("systemNotification"), content: mask(p.content, 200) };
      if (p._meta) out._meta = { ...(p._meta.kind ? { kind: label(p._meta.kind) } : {}), ...(p._meta.severity ? { severity: label(p._meta.severity) } : {}) };
      return out;
    }
    case "toolCall":
      return { kind: label("toolCall"), toolCall: toolCall(p.toolCall) };
    default:
      return fail(`unhandled response part kind "${p.kind}": extend tools/redact-chat.mjs (whitelist) before fixtures can be built`);
  }
}

const turnMessage = (m) => {
  only(m, ["text", "origin", "model", "agent"], "message");
  const out = { text: mask(m.text, 300), origin: { kind: label(m.origin.kind) } };
  if (m.model) {
    seen(m.model.id);
    out.model = { id: MODEL };
  }
  return out;
};

function turn(t, minute, { active = false } = {}) {
  only(t, ["id", "message", "responseParts", "startedAt", "state", "duration", "usage"], active ? "active turn" : "turn");
  const out = { id: fakeId("turn", t.id), message: turnMessage(t.message), responseParts: t.responseParts.map(part), startedAt: iso(minute) };
  if (!active) {
    out.state = label(t.state);
    if (typeof t.duration === "number") out.duration = t.duration;
    if (t.usage) out.usage = usage(t.usage);
  }
  return out;
}

// ---- actions --------------------------------------------------------------------------------------------------
const ACTION_TYPES = ["chat/toolCallStart", "chat/toolCallDelta", "chat/toolCallReady", "chat/toolCallContentChanged", "chat/toolCallComplete", "chat/responsePart", "chat/reasoning", "chat/usage"];
function action(a) {
  if (!ACTION_TYPES.includes(a.type)) fail(`unhandled chat action type "${a.type}": extend tools/redact-chat.mjs (whitelist) before fixtures can be built`);
  const out = { type: label(a.type) };
  if (a.turnId !== undefined) out.turnId = fakeId("turn", a.turnId);
  if (a.toolCallId !== undefined) out.toolCallId = fakeId("call", a.toolCallId);
  if (a.partId !== undefined) out.partId = fakeId("part", a.partId);
  if (a.toolName !== undefined) out.toolName = fakeId("tool", a.toolName);
  if (a.displayName !== undefined) out.displayName = mask(a.displayName, 60);
  if (a.intention !== undefined) out.intention = mask(a.intention, 200);
  if (a.confirmed !== undefined) out.confirmed = label(a.confirmed);
  if (a.invocationMessage !== undefined) out.invocationMessage = message(a.invocationMessage);
  if (a.toolInput !== undefined) out.toolInput = toolInput(a.toolInput);
  if (a.content !== undefined) out.content = typeof a.content === "string" ? mask(a.content, 200) : a.content.map(content);
  if (a.part !== undefined) out.part = part(a.part);
  if (a.result !== undefined) {
    out.result = { success: a.result.success, pastTenseMessage: message(a.result.pastTenseMessage) };
    if (a.result.content) out.result.content = a.result.content.map(content);
  }
  if (a.usage !== undefined) out.usage = usage(a.usage);
  const meta = a.type.startsWith("chat/toolCall") ? toolMeta(a._meta) : undefined;
  if (meta) out._meta = meta;
  only(a, ["type", "turnId", "toolCallId", "partId", "toolName", "displayName", "intention", "confirmed", "invocationMessage", "toolInput", "content", "part", "result", "usage", "_meta"], a.type);
  return out;
}

// ---- assemble -------------------------------------------------------------------------------------------------
only(state, ["resource", "title", "status", "modifiedAt", "turns", "activeTurn", "origin", "changes", "changesets", "draft", "backgroundWork", "turnsNextCursor"], "chat state");
const selection = pickTurns(state.turns);
const stateOut = {
  resource: FAKE_CHAT,
  title: "Chat A",
  status: state.status,
  modifiedAt: iso(selection.turns.length * 10 + 5),
  origin: { kind: label(state.origin.kind) },
  turns: selection.turns.map((t, i) => turn(t, i * 10)),
};
if (state.changes) stateOut.changes = { additions: state.changes.additions, deletions: state.changes.deletions, files: state.changes.files };
if (state.activeTurn) stateOut.activeTurn = turn(state.activeTurn, selection.turns.length * 10, { active: true });
seen(state.resource);
seen(state.title);

const outputs = {
  "chat-subscribe-result.json": { snapshot: { resource: FAKE_CHAT, fromSeq: snapshot.fromSeq, state: stateOut } },
  "chat-actions.json": realActions.map((p) => ({ channel: FAKE_CHAT, serverSeq: p.serverSeq, action: action(p.action) })),
};
const serialized = Object.fromEntries(Object.entries(outputs).map(([name, body]) => [name, JSON.stringify(body, null, 2) + "\n"]));

// ---- prove nothing leaked, then write -------------------------------------------------------------------------
const wholeCapture = new Set();
const collectStrings = (v) => {
  if (typeof v === "string") wholeCapture.add(v);
  else if (Array.isArray(v)) v.forEach(collectStrings);
  else if (v && typeof v === "object") Object.values(v).forEach(collectStrings);
};
collectStrings(snapshot);
realActions.forEach(collectStrings);

const problems = findLeaks({
  outputs: serialized,
  originals: wholeCapture,
  labels,
  extraWords: [...KEEP_LANGUAGES, ...FAKE_WORDS, ...[...KEEP_INPUT_KEYS].flatMap((k) => k.replace(/([a-z])([A-Z])/g, "$1 $2").split(" ")), "true", "false", "null"],
  placeholderWords: LOREM,
  safeStrings: new Set([PNG_1X1, LOREM_BASE64]),
  username: userInfo().username,
});
for (const [name, text] of Object.entries(serialized)) if (Buffer.byteLength(text) > MAX_FILE_BYTES) problems.push(`${name} is ${Buffer.byteLength(text)} bytes, over the ${MAX_FILE_BYTES} limit`);
if (problems.length) fail("REDACTION FAILED, nothing written:\n  " + [...new Set(problems)].join("\n  "));

mkdirSync(outDir, { recursive: true });
for (const [name, text] of Object.entries(serialized)) writeFileSync(join(outDir, name), text);

const kinds = {};
const contentTypes = {};
for (const t of [...stateOut.turns, ...(stateOut.activeTurn ? [stateOut.activeTurn] : [])]) {
  for (const p of t.responseParts) {
    kinds[p.kind] = (kinds[p.kind] ?? 0) + 1;
    for (const c of p.toolCall?.content ?? []) contentTypes[c.type] = (contentTypes[c.type] ?? 0) + 1;
  }
}
const actionTypes = {};
for (const e of outputs["chat-actions.json"]) actionTypes[e.action.type] = (actionTypes[e.action.type] ?? 0) + 1;
console.log(`wrote ${Object.keys(serialized).length} fixtures to ${outDir}`);
console.log(`  chat-subscribe-result.json: ${Buffer.byteLength(serialized["chat-subscribe-result.json"])} bytes, ${stateOut.turns.length} of ${selection.from} turns + ${stateOut.activeTurn ? "the active turn" : "no active turn"}; parts ${JSON.stringify(kinds)}; tool content ${JSON.stringify(contentTypes)}`);
console.log(`  chat-actions.json: ${Buffer.byteLength(serialized["chat-actions.json"])} bytes, ${outputs["chat-actions.json"].length} actions ${JSON.stringify(actionTypes)}`);
console.log(`  coverage tokens (${selection.covered.length}): ${selection.covered.join(", ")}`);
if (dropped.size) console.log(`  fields this script has not seen before (dropped; check the decode test): ${[...dropped].join(" | ")}`);
