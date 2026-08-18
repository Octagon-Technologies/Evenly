#!/usr/bin/env node
/**
 * secret-scan — on `git add`, warns if a *newly tracked* file looks like a credential, or
 * if a staged file's content matches a common secret pattern (AGENTS.md non-negotiable #6:
 * never commit secrets; AI_WORKFLOW_PLAYBOOK.md §2).
 *
 * google-services.json and GoogleService-Info.plist are intentionally tracked already
 * (public, RLS-gated / no embedded secret) — this only flags files that are new to git,
 * so re-adding those two after an edit doesn't trigger noise every time.
 *
 * Advisory only (additionalContext): a match here is a strong prompt to double-check
 * before this reaches a commit, not proof of a real leak.
 */

const { execSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const NAME_PATTERNS = [
  /\.p8$/i,
  /\.p12$/i,
  /\.jks$/i,
  /\.keystore$/i,
  /serviceAccount.*\.json$/i,
  /\.pem$/i,
  /^local\.properties$/i,
];

const CONTENT_PATTERNS = [
  /-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----/,
  /AKIA[0-9A-Z]{16}/, // AWS access key
  /xox[baprs]-[0-9A-Za-z-]{10,}/, // Slack token
  /sk-[A-Za-z0-9]{20,}/, // generic secret-key-shaped token (OpenAI-style, etc.)
  /-----BEGIN CERTIFICATE-----/,
];

let raw = "";
process.stdin.on("data", (d) => (raw += d));
process.stdin.on("end", () => {
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    process.exit(0);
  }

  if (payload.tool_name !== "Bash") process.exit(0);
  const cmd = (payload.tool_input || {}).command || "";
  if (!/\bgit\s+add\b/.test(cmd)) process.exit(0);

  const cwd = process.env.CLAUDE_PROJECT_DIR || payload.cwd || ".";

  // Scope arg extraction to the single line containing "git add" (not the whole command
  // string) — a multi-line Bash invocation would otherwise leak unrelated tokens in as "args".
  const addLine = cmd.split("\n").find((l) => /\bgit\s+add\b/.test(l)) || "";
  const args = addLine
    .replace(/^.*\bgit\s+add\b/, "")
    .split(/(?:&&|\|\||;)/)[0]
    .trim()
    .split(/\s+/)
    .filter((a) => a && !a.startsWith("-"));
  if (args.length === 0) process.exit(0); // `git add -A`/`-u` with no explicit paths: nothing to inspect cheaply

  let trackedFiles = new Set();
  try {
    trackedFiles = new Set(execSync("git ls-files", { cwd, encoding: "utf8" }).split("\n"));
  } catch {
    process.exit(0);
  }

  const hits = [];
  for (const arg of args) {
    const relPath = arg.replace(/^\.\//, "");
    const isNew = !trackedFiles.has(relPath);
    const base = path.basename(relPath);

    if (isNew && NAME_PATTERNS.some((p) => p.test(base))) {
      hits.push(`${relPath} — filename looks like a credential, and it's new to git`);
      continue;
    }

    const abs = path.resolve(cwd, arg);
    try {
      if (fs.statSync(abs).isFile() && fs.statSync(abs).size < 2_000_000) {
        const content = fs.readFileSync(abs, "utf8");
        const matched = CONTENT_PATTERNS.find((p) => p.test(content));
        if (matched) hits.push(`${relPath} — content matches a secret pattern (${matched})`);
      }
    } catch {
      // unreadable/binary/missing — skip
    }
  }

  if (hits.length === 0) process.exit(0);

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        additionalContext:
          `secret-scan: possible credential in files being staged:\n` +
          hits.map((h) => `  • ${h}`).join("\n") +
          `\n\nDouble-check before this reaches a commit. If it's a false positive (e.g. a ` +
          `fixture or test key), proceed.`,
      },
    })
  );
});
