#!/usr/bin/env node
/**
 * em-dash-guard — denies em dashes (U+2014) inside Kotlin STRING LITERALS.
 *
 * Evenly has a hard rule: no em dashes in user-facing copy. The rule explicitly
 * exempts comments, KDoc, and docs, so this scanner tracks quote state and only ever
 * fires on a dash that is genuinely inside an open string. That narrow scope is what
 * buys the right to `deny` rather than `ask` — a false positive here would be expensive
 * (this repo has ~626 em-dash lines in commonMain, almost all of them comments).
 *
 * Escape hatch: put `agents-allow-dash` in a comment on the same line.
 */

let raw = "";
process.stdin.on("data", (d) => (raw += d));
process.stdin.on("end", () => {
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    process.exit(0); // never block on a malformed event
  }

  const tool = payload.tool_name || "";
  if (!["Write", "Edit", "MultiEdit"].includes(tool)) process.exit(0);

  const ti = payload.tool_input || {};
  const file = ti.file_path || "";

  // Kotlin sources only. Tests and generated code are exempt.
  if (!/\.kt$/.test(file)) process.exit(0);
  if (/\/(commonTest|iosTest|androidHostTest|androidUnitTest|test)\//i.test(file)) process.exit(0);
  if (/\/build\//.test(file)) process.exit(0);

  const chunks = [];
  if (typeof ti.content === "string") chunks.push(ti.content);
  if (typeof ti.new_string === "string") chunks.push(ti.new_string);
  if (Array.isArray(ti.edits)) {
    for (const e of ti.edits) {
      if (e && typeof e.new_string === "string") chunks.push(e.new_string);
    }
  }

  const hits = [];
  for (const c of chunks) hits.push(...scanKotlin(c));
  if (hits.length === 0) process.exit(0);

  const shown = hits.slice(0, 5).map((h) => `  • ${h.text}`).join("\n");
  const more = hits.length > 5 ? `\n  …and ${hits.length - 5} more` : "";
  const reason =
    `Em dash (—) found inside ${hits.length} Kotlin string literal(s) in ${file}:\n${shown}${more}\n\n` +
    `Evenly rule: never use em dashes in user-facing strings. Rewrite with a period, ` +
    `comma, or parentheses — e.g. "It uploads after you save." not "It uploads — after you save."\n` +
    `Comments and KDoc are exempt (this check only reads string literals). ` +
    `If a dash is genuinely required, add "agents-allow-dash" in a comment on that line.`;

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "deny",
        permissionDecisionReason: reason,
      },
    })
  );
});

/** Returns [{line, text}] for every em dash sitting inside a Kotlin string literal. */
function scanKotlin(src) {
  const lines = src.split("\n");
  const allow = lines.map((l) => /agents-allow-dash/.test(l));
  const out = [];

  let i = 0;
  let line = 0;
  // N = normal, L = line comment, B = block comment, S = string, R = raw string
  let state = "N";

  while (i < src.length) {
    const ch = src[i];
    const nx = src[i + 1];

    if (ch === "\n") {
      line++;
      if (state === "L") state = "N";
      i++;
      continue;
    }

    if (state === "N") {
      if (ch === "/" && nx === "/") { state = "L"; i += 2; continue; }
      if (ch === "/" && nx === "*") { state = "B"; i += 2; continue; }
      if (src.startsWith('"""', i)) { state = "R"; i += 3; continue; }
      if (ch === '"') { state = "S"; i++; continue; }
      i++;
      continue;
    }

    if (state === "L") { i++; continue; }

    if (state === "B") {
      if (ch === "*" && nx === "/") { state = "N"; i += 2; continue; }
      i++;
      continue;
    }

    if (state === "S") {
      if (ch === "\\") { i += 2; continue; } // escaped char, incl. \"
      if (ch === '"') { state = "N"; i++; continue; }
      if (ch === "—" && !allow[line]) out.push({ line: line + 1, text: (lines[line] || "").trim() });
      i++;
      continue;
    }

    if (state === "R") {
      if (src.startsWith('"""', i)) { state = "N"; i += 3; continue; }
      if (ch === "—" && !allow[line]) out.push({ line: line + 1, text: (lines[line] || "").trim() });
      i++;
      continue;
    }
  }

  return out;
}
