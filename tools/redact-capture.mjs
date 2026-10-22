// Builds redacted test fixtures from a local probe capture (captures/*.jsonl).
//
//   node tools/redact-capture.mjs captures/live-session.jsonl
//
// Fixtures are built from a WHITELIST of fields, never by deleting from the original, so a new field in a future
// protocol version cannot leak by default. Titles, ids, paths, project names and git metadata are replaced; the
// script then checks that none of the original strings survive in the output and exits non-zero if one does.
// The raw capture stays local: captures/ is gitignored.

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { userInfo } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const username = userInfo().username;
const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

const here = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(here, "..", "core-ahp", "src", "test", "resources", "live");
const input = process.argv[2];
if (!input) {
  console.error("usage: node tools/redact-capture.mjs <capture.jsonl>");
  process.exit(2);
}

const frames = readFileSync(input, "utf8")
  .split("\n")
  .filter(Boolean)
  .map((line) => JSON.parse(line))
  .filter((row) => row.dir === "in")
  .map((row) => JSON.parse(row.frame));

const init = frames.find((f) => f.result?.snapshots)?.result;
const list = frames.find((f) => f.result?.items)?.result;
if (!init || !list) {
  console.error("capture has no initialize result or listSessions result");
  process.exit(1);
}

// ---- fake ids, stable across fixtures -----------------------------------------------------------------------
const originals = new Set(); // every real string we replaced, for the leak check
const fakeSessions = new Map();
const sessionUri = (real) => {
  originals.add(real);
  if (!fakeSessions.has(real)) {
    const n = fakeSessions.size + 1;
    fakeSessions.set(real, `ahp-session:/00000000-0000-4000-8000-${String(n).padStart(12, "0")}`);
  }
  return fakeSessions.get(real);
};
// The host encodes a chat URI as ahp-chat://default/<base64url(session uri)>.
const chatUri = (realChat, fakeSession) => {
  originals.add(realChat);
  return `ahp-chat://default/${Buffer.from(fakeSession).toString("base64url")}`;
};
// A fork origin points at the chat it was forked from: map it through the session it encodes so ids stay consistent.
const forkSourceChat = (realChat) => {
  originals.add(realChat);
  const encoded = /^ahp-chat:\/\/default\/(.+)$/.exec(realChat)?.[1];
  if (!encoded) return "ahp-chat://default/redacted";
  const realSession = Buffer.from(encoded, "base64url").toString("utf8");
  return `ahp-chat://default/${Buffer.from(sessionUri(realSession)).toString("base64url")}`;
};
let turnCounter = 0;

const remember = (...values) => values.filter((v) => typeof v === "string" && v.length > 3).forEach((v) => originals.add(v));

// ---- session fixtures ---------------------------------------------------------------------------------------
const ARCHIVED = 64, READ = 32, IN_PROGRESS = 8;
const has = (s, flag) => (s.status & flag) === flag;
const pickFirst = (predicate) => list.items.find(predicate);

const realPicks = [
  { label: "archived, idle, with project and changes", real: pickFirst((s) => has(s, ARCHIVED) && s.project && s.changes && s.chats?.length === 1) },
  { label: "read, idle", real: pickFirst((s) => s.status === 33 && !s.project) ?? pickFirst((s) => s.status === 33) },
  { label: "in progress", real: pickFirst((s) => has(s, IN_PROGRESS) && s.project) ?? pickFirst((s) => has(s, IN_PROGRESS)) },
  { label: "three chats", real: pickFirst((s) => s.chats?.length >= 3) },
].filter((p) => p.real);
// Fix the ids of the listed sessions first, so mapping a fork's source session cannot renumber them.
realPicks.forEach((p) => sessionUri(p.real.resource));

const letters = "ABCDEFGH";
function redactSession(real, index, overrides = {}) {
  remember(real.title, real.project?.displayName, real.project?.uri, ...(real.workingDirectories ?? []), ...(real.chats ?? []).flatMap((c) => [c.title]));
  const uri = sessionUri(real.resource);
  const day = String(20 + index).padStart(2, "0");
  const out = {
    resource: uri,
    provider: real.provider,
    title: `Session ${letters[index]}`,
    status: real.status,
    createdAt: `2026-10-${day}T09:00:00.000Z`,
    modifiedAt: `2026-10-${day}T10:30:00.000Z`,
  };
  if (real.project) out.project = { uri: `file:///work/repo-${letters[index].toLowerCase()}`, displayName: `repo-${letters[index].toLowerCase()}` };
  if (real.workingDirectories?.length) out.workingDirectories = [`file:///work/repo-${letters[index].toLowerCase()}`];
  if (real.changes) out.changes = { additions: real.changes.additions, deletions: real.changes.deletions, files: real.changes.files };
  if (real.chats?.length) {
    out.chats = real.chats.map((c, i) => {
      const chat = {
        resource: chatUri(c.resource, uri) + (i === 0 ? "" : `-${i}`),
        title: `Chat ${i + 1}`,
        status: c.status,
        interactivity: c.interactivity,
      };
      if (c.origin?.kind === "fork") {
        remember(c.origin.turnId);
        chat.origin = { kind: "fork", chat: forkSourceChat(c.origin.chat), turnId: `turn-${++turnCounter}` };
      } else if (c.origin?.kind) {
        chat.origin = { kind: c.origin.kind };
      }
      if (c.changes) chat.changes = { additions: c.changes.additions, deletions: c.changes.deletions, files: c.changes.files };
      return chat;
    });
    out.defaultChat = out.chats[0].resource;
  }
  return { ...out, ...overrides };
}

const items = realPicks.map((p, i) => redactSession(p.real, i));
const base = realPicks.find((p) => has(p.real, IN_PROGRESS))?.real ?? realPicks[0].real;
// Not present in the capture, so synthesized from a real in-progress item: the states a monitor cares about most.
items.push({ ...redactSession(base, items.length, { status: 24 | READ }), title: "Session E (synthetic: input needed)", resource: "ahp-session:/00000000-0000-4000-8000-0000000000e1" });
items.push({ ...redactSession(base, items.length, { status: 2 }), title: "Session F (synthetic: error)", resource: "ahp-session:/00000000-0000-4000-8000-0000000000e2" });

// ---- root snapshot ------------------------------------------------------------------------------------------
const snapshot = init.snapshots[0];
const agent = snapshot.state.agents[0];
const rootResult = {
  protocolVersion: init.protocolVersion,
  serverSeq: init.serverSeq,
  snapshots: [
    {
      resource: snapshot.resource,
      fromSeq: snapshot.fromSeq,
      state: {
        agents: [
          {
            provider: agent.provider,
            displayName: agent.displayName,
            description: agent.description,
            models: (agent.models ?? []).slice(0, 2).map((m) => ({ id: m.id, provider: m.provider, name: m.name, supportsVision: m.supportsVision })),
            capabilities: agent.capabilities,
          },
        ],
        activeSessions: snapshot.state.activeSessions,
        terminals: [],
        config: { schema: { type: "object", properties: {} }, values: {} },
      },
    },
  ],
  completionTriggerCharacters: init.completionTriggerCharacters,
  terminalCommandPrefix: init.terminalCommandPrefix,
};

// ---- a summary-changed notification: the shape is real (session + changes); the values are synthetic ---------
const changed = {
  channel: "ahp-root://",
  session: items[2].resource,
  changes: { status: 24 | READ, activity: "Waiting for approval", modifiedAt: "2026-10-22T11:00:00.000Z" },
};

// ---- write, then prove nothing leaked -----------------------------------------------------------------------
mkdirSync(outDir, { recursive: true });
const outputs = {
  "list-sessions.json": { items },
  "initialize-result.json": rootResult,
  "session-summary-changed.json": changed,
};
const serialized = Object.fromEntries(Object.entries(outputs).map(([name, body]) => [name, JSON.stringify(body, null, 2) + "\n"]));

const leaks = [];
for (const [name, text] of Object.entries(serialized)) {
  for (const secret of originals) if (secret && text.includes(secret)) leaks.push(`${name} contains an original value (${secret.length} chars)`);
  for (const pattern of [/[A-Za-z]:\\\\/, /\/Users\//i, /\/home\//i, new RegExp(escapeRegExp(username), "i"), /@/]) {
    const m = text.match(pattern);
    if (m && !(pattern.source === "@" && name === "initialize-result.json")) leaks.push(`${name} matches ${pattern} near "${m[0]}"`);
  }
  const uuids = text.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi) ?? [];
  for (const u of uuids) if (!u.startsWith("00000000-0000-4000-8000-")) leaks.push(`${name} contains a real-looking uuid ${u}`);
}
if (leaks.length) {
  console.error("REDACTION FAILED, nothing written:\n  " + leaks.join("\n  "));
  process.exit(1);
}
for (const [name, text] of Object.entries(serialized)) writeFileSync(join(outDir, name), text);
console.log(`wrote ${Object.keys(serialized).length} fixtures to ${outDir}: ${items.length} sessions (${realPicks.map((p) => p.label).join("; ")}; 2 synthetic)`);
