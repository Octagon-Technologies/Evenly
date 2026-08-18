#!/usr/bin/env node
/**
 * pre-commit-lint — on `git commit`, runs ktlint --format (auto-fix, re-staged) then
 * detekt in baseline mode (report-only, no rewrite) against staged Kotlin files.
 * Style/static-analysis correctness shouldn't cost an LLM judgment call (AI_WORKFLOW_PLAYBOOK.md §2).
 *
 * Runs once, at a stable point (pre-commit), not on every Write/Edit — a post-write
 * auto-format races with in-progress Edit calls (the tool's old_string can stop matching
 * after a silent reformat) and with any other session editing the same file concurrently.
 *
 * ktlint --format is safe to auto-apply (style/formatting only, no rewrite risk to logic).
 * detekt is report-only and blocks the commit on NEW findings — .claude/config/detekt-baseline.xml
 * grandfathers pre-existing debt so this doesn't retroactively block on old code.
 */

const { execSync, execFileSync } = require("child_process");
const path = require("path");

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
  if (!/\bgit\s+commit\b/.test(cmd)) process.exit(0);
  if (/--no-verify\b/.test(cmd)) process.exit(0);

  const cwd = process.env.CLAUDE_PROJECT_DIR || payload.cwd || ".";

  let staged;
  try {
    staged = execSync("git diff --cached --name-only --diff-filter=ACMR", { cwd, encoding: "utf8" })
      .split("\n")
      .filter((f) => f.endsWith(".kt"));
  } catch {
    process.exit(0);
  }
  if (staged.length === 0) process.exit(0);

  if (!hasBinary("ktlint") || !hasBinary("detekt")) {
    process.stdout.write(
      JSON.stringify({
        hookSpecificOutput: {
          hookEventName: "PreToolUse",
          additionalContext:
            "pre-commit-lint: ktlint and/or detekt not found on PATH — skipped. Install both " +
            "(`brew install ktlint detekt`) to restore this check.",
        },
      })
    );
    return;
  }

  // 1. ktlint --format, auto-fix in place, re-stage anything it touched.
  try {
    execFileSync("ktlint", ["--format", ...staged], { cwd, stdio: "pipe" });
  } catch {
    // ktlint exits non-zero if it fixed something or found unfixable issues — either way, continue.
  }
  try {
    execFileSync("git", ["add", ...staged], { cwd, stdio: "pipe" });
  } catch {
    // best-effort re-stage
  }

  // 2. detekt, baseline mode — only NEW findings are reported.
  const baseline = path.resolve(cwd, ".claude/config/detekt-baseline.xml");
  let detektOutput = "";
  let detektFailed = false;
  try {
    execFileSync(
      "detekt",
      [
        "--input",
        "code/shared/src,code/androidApp/src",
        "--build-upon-default-config",
        "--baseline",
        baseline,
      ],
      { cwd, stdio: "pipe" }
    );
  } catch (e) {
    detektFailed = true;
    detektOutput = ((e.stdout || "") + (e.stderr || "")).toString();
  }

  if (!detektFailed) {
    process.exit(0);
  }

  const summary = detektOutput
    .split("\n")
    .filter((l) => l.trim())
    .slice(0, 40)
    .join("\n");

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "ask",
        permissionDecisionReason:
          `pre-commit-lint: detekt found new issue(s) not in the baseline:\n\n${summary}\n\n` +
          `Fix them, or if a rule shouldn't apply here, suppress it inline (@Suppress) with a ` +
          `reason rather than editing the baseline. Proceed only if these are false positives.`,
      },
    })
  );
});

function hasBinary(name) {
  try {
    execFileSync("which", [name], { stdio: "pipe" });
    return true;
  } catch {
    return false;
  }
}
