// Leak checks for the redactors. A redactor builds its output from a whitelist and masks every free-text value, so
// this is the second, independent line of defence: it looks at the finished output and at the original capture
// and refuses the write if the two share anything they should not. It never returns leaked text, only where and how long.

const GRAM = 16;
const MIN_LETTERS_IN_GRAM = 6;
const MIN_ORIGINAL = 6;

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;
const FAKE_UUID_PREFIX = "00000000-0000-4000-8000-";

const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const splitIdentifier = (s) => s.replace(/([a-z])([A-Z])/g, "$1 $2").match(/\p{L}+/gu) ?? [];
const letters = (s) => splitIdentifier(s).filter((w) => w.length >= 4);
const interiorWords = (gram) => {
  const words = [];
  for (const m of gram.matchAll(/\p{L}+/gu)) {
    if (m.index === 0 || m.index + m[0].length === gram.length) continue;
    words.push(...letters(m[0]));
  }
  return words;
};

function collect(value, strings, keys) {
  if (typeof value === "string") strings.push(value);
  else if (Array.isArray(value)) value.forEach((x) => collect(x, strings, keys));
  else if (value && typeof value === "object") {
    for (const [k, x] of Object.entries(value)) {
      keys.add(k);
      collect(x, strings, keys);
    }
  }
}

/**
 * @param outputs      file name -> serialized JSON the redactor is about to write
 * @param originals    every real string from the capture that the redactor saw or replaced
 * @param labels       real values that are passed through on purpose (protocol enum values such as "completed")
 * @param extraWords   structural words the output may contain (fake id prefixes, language tags, kept tool-input keys)
 * @param placeholderWords  the placeholder words free text is masked into; allowed, but not structural
 * @param safeStrings  whole strings the output may contain verbatim (generated constants)
 * @param username     the OS user name, which must never appear
 * @returns a list of problems; empty means clean
 */
export function findLeaks({ outputs, originals, labels = new Set(), extraWords = [], placeholderWords = [], safeStrings = new Set(), username = "" }) {
  const problems = [];
  const structural = new Set(extraWords.map((w) => w.toLowerCase()));
  for (const label of labels) for (const w of splitIdentifier(label)) structural.add(w.toLowerCase());

  const parsed = {};
  for (const [name, text] of Object.entries(outputs)) {
    const strings = [];
    const keys = new Set();
    collect(JSON.parse(text), strings, keys);
    parsed[name] = strings;
    for (const key of keys) for (const w of splitIdentifier(key)) structural.add(w.toLowerCase());
  }
  const vocabulary = new Set([...structural, ...placeholderWords.map((w) => w.toLowerCase())]);

  const originalSet = new Set([...originals].filter((s) => typeof s === "string" && s.length >= MIN_ORIGINAL && !labels.has(s)));
  const usernamePattern = username ? new RegExp(escapeRegExp(username), "i") : null;
  const patterns = [
    [/\b[A-Za-z]:\\/, "a Windows drive path"],
    [/[A-Za-z]%3A/i, "an encoded drive letter"],
    [/\/Users\//i, "a /Users/ path"],
    [/\/home\//i, "a /home/ path"],
    [/\\Users\\/i, "a \\Users\\ path"],
    [/gh[pousr]_[A-Za-z0-9]{20,}/, "a GitHub token"],
    [/eyJ[A-Za-z0-9_-]{10,}/, "a JWT"],
    [/Bearer\s+\S{10,}/, "a bearer token"],
  ];

  // The 16-character windows of every output string; an original sharing one of them has leaked.
  const outputGrams = new Set();
  for (const strings of Object.values(parsed)) {
    for (const s of strings) {
      if (safeStrings.has(s)) continue;
      for (let i = 0; i + GRAM <= s.length; i++) outputGrams.add(s.slice(i, i + GRAM));
    }
  }

  for (const [name, strings] of Object.entries(parsed)) {
    const unknown = new Set();
    for (const s of strings) {
      if (safeStrings.has(s)) continue;
      if (originalSet.has(s)) problems.push(`${name}: a string equal to an original value (${s.length} chars)`);
      if (usernamePattern?.test(s)) problems.push(`${name}: contains the OS user name`);
      for (const [pattern, what] of patterns) if (pattern.test(s)) problems.push(`${name}: contains ${what}`);
      for (const uuid of s.match(UUID) ?? []) if (!uuid.startsWith(FAKE_UUID_PREFIX)) problems.push(`${name}: contains a real-looking uuid`);
      for (const w of letters(s)) if (!vocabulary.has(w.toLowerCase())) unknown.add(w.toLowerCase());
    }
    if (unknown.size) problems.push(`${name}: ${unknown.size} word(s) outside the placeholder vocabulary, longest ${Math.max(...[...unknown].map((w) => w.length))} letters`);
  }

  if (outputGrams.size) {
    for (const original of originalSet) {
      if (original.length < GRAM) continue;
      for (let i = 0; i + GRAM <= original.length; i++) {
        const gram = original.slice(i, i + GRAM);
        if (!outputGrams.has(gram)) continue;
        if ((gram.match(/\p{L}/gu) ?? []).length < MIN_LETTERS_IN_GRAM) continue;
        // A run made only of structural words (keys, labels, fence languages) is boilerplate, not content.
        // Words touching the window's edge may be cut in half, so they are left to the neighbouring window.
        if (interiorWords(gram).every((w) => structural.has(w.toLowerCase()))) continue;
        problems.push(`output shares a ${GRAM}-character run with an original string of ${original.length} chars`);
        break;
      }
    }
  }
  return problems;
}
