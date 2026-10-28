// Builds a redacted fixture of the git `_meta` a host puts on listed sessions, from a local probe capture (captures/*.jsonl).
//
//   node tools/redact-meta.mjs captures/capture-vscode.jsonl vscode-session-meta.json
//
// The other redactors drop `_meta` whole, because it carries paths, branch names and quota data. This one keeps only the git
// object that HostExtras reads (and `gitData.<folder>` for a multi-root workspace), from a whitelist of keys: branch names become
// placeholders, counts and flags are kept, every other key is dropped. It picks one session per distinct set of git keys, so the
// fixture shows each shape the host sent. Nothing is written if findLeaks finds an original string in the output.

import { readFileSync, writeFileSync } from "node:fs";
import { userInfo } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { findLeaks } from "./redaction-check.mjs";

const [input, outName] = process.argv.slice(2);
if (!input || !outName) {
  console.error("usage: node tools/redact-meta.mjs <capture.jsonl> <output-name.json>");
  process.exit(2);
}
const outPath = resolve(dirname(fileURLToPath(import.meta.url)), "..", "core-ahp", "src", "test", "resources", "live", outName);

const frames = readFileSync(input, "utf8")
  .split("\n")
  .filter(Boolean)
  .map((line) => JSON.parse(line))
  .filter((row) => row.dir === "in")
  .map((row) => JSON.parse(row.frame));
const items = frames.find((f) => f.result?.items)?.result.items ?? [];

const TEXT_KEYS = ["branchName", "baseBranchName", "defaultBranchName", "defaultRemoteBranchName", "upstreamBranchName"];
const COUNT_KEYS = ["outgoingChanges", "incomingChanges", "uncommittedChanges"];
const FLAG_KEYS = ["hasBaseBranchChanges", "hasGitHubRemote", "hasGitRemote", "isDetachedHead"];

const originals = new Set();
const fakes = new Map();
const fake = (role, real) => {
  originals.add(real);
  const key = `${role}:${real}`;
  if (!fakes.has(key)) fakes.set(key, `${role}-redacted-${fakes.size + 1}`);
  return fakes.get(key);
};

const redactGit = (git) => {
  if (!git || typeof git !== "object" || Array.isArray(git)) return null;
  const out = {};
  for (const key of TEXT_KEYS) if (typeof git[key] === "string") out[key] = fake(key.replace("Name", ""), git[key]);
  for (const key of COUNT_KEYS) if (Number.isInteger(git[key])) out[key] = Math.min(Math.max(git[key], 0), 1000);
  for (const key of FLAG_KEYS) if (typeof git[key] === "boolean") out[key] = git[key];
  return Object.keys(out).length ? out : null;
};

const seen = new Map();
for (const item of items) {
  const meta = item._meta;
  if (!meta) continue;
  const out = {};
  const git = redactGit(meta.git);
  if (git) out.git = git;
  if (meta.gitData && typeof meta.gitData === "object") {
    const folders = {};
    Object.values(meta.gitData).slice(0, 3).forEach((folder, i) => {
      const g = redactGit(folder);
      if (g) folders[`folder-${i + 1}`] = g;
    });
    if (Object.keys(folders).length) out.gitData = folders;
  }
  if (typeof meta["vscode.external"] === "boolean") out["vscode.external"] = meta["vscode.external"];
  if (typeof meta.workspaceless === "boolean") out.workspaceless = meta.workspaceless;
  const shape = JSON.stringify({ git: Object.keys(out.git ?? {}).sort(), gitData: Object.keys(Object.values(out.gitData ?? {})[0] ?? {}).sort(), rest: Object.keys(out).sort() });
  if (!seen.has(shape)) seen.set(shape, out);
}

const metas = [...seen.values()].slice(0, 12);
if (!metas.length) {
  console.error("capture has no listed session with git metadata");
  process.exit(1);
}
const body = JSON.stringify({ metas }, null, 2) + "\n";

const leaks = findLeaks({
  outputs: { [outName]: body },
  originals,
  extraWords: ["redacted", "folder", "branch", "base", "default", "defaultRemote", "upstream"],
  username: userInfo().username,
});
if (leaks.length) {
  console.error(`refusing to write: ${leaks.length} possible leak(s)`);
  leaks.forEach((l) => console.error(" ", JSON.stringify(l)));
  process.exit(1);
}
writeFileSync(outPath, body);
console.log(`wrote ${outPath}: ${metas.length} metas from ${items.length} listed sessions`);
