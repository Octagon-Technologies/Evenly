#!/usr/bin/env node
/**
 * block-risky-git — denies git invocations that skip safety checks or force-push
 * over main/master, so these can't be talked around mid-session (AI_WORKFLOW_PLAYBOOK.md §2).
 *
 * Hard-blocked, no ask/override: --no-verify, --no-gpg-sign, -c commit.gpgsign=false,
 * and any force push (--force/-f/--force-with-lease) that targets main or master.
 * A force push to a feature branch (e.g. after a rebase) is left alone.
 */

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
  if (!/\bgit\b/.test(cmd)) process.exit(0);

  const cwd = process.env.CLAUDE_PROJECT_DIR || payload.cwd || ".";

  for (const part of splitChained(cmd)) {
    const reason = checkPart(part.trim(), cwd);
    if (reason) return deny(reason);
  }

  process.exit(0);
});

function splitChained(cmd) {
  // Good enough for our purposes: split on shell chaining operators and newlines, not inside quotes.
  return cmd.split(/&&|\|\||;|\n/);
}

function checkPart(part, cwd) {
  if (!/\bgit\b/.test(part)) return null;

  if (/--no-verify\b/.test(part)) {
    return `Blocked: "--no-verify" skips commit hooks. ${part.trim()}`;
  }
  if (/--no-gpg-sign\b/.test(part) || /-c\s+commit\.gpgsign=false/.test(part)) {
    return `Blocked: this bypasses commit signing. ${part.trim()}`;
  }

  if (/\bgit\s+push\b/.test(part) && /(--force\b|--force-with-lease\b|(^|\s)-f\b)/.test(part)) {
    const target = forcePushTarget(part, cwd);
    if (target === "main" || target === "master") {
      return `Blocked: force push targets "${target}". ${part.trim()}`;
    }
  }

  return null;
}

function forcePushTarget(part, cwd) {
  // Look for an explicit "<remote> <branch>" or "<remote> <branch>:<remote-branch>" argument.
  const tokens = part
    .replace(/^.*\bgit\s+push\b/, "")
    .trim()
    .split(/\s+/)
    .filter((t) => t && !t.startsWith("-"));

  if (tokens.length >= 2) {
    const ref = tokens[1].split(":")[0].replace(/^\+/, "");
    return ref;
  }

  // No explicit branch: falls back to the current branch.
  try {
    const { execSync } = require("child_process");
    return execSync("git rev-parse --abbrev-ref HEAD", { cwd, encoding: "utf8" }).trim();
  } catch {
    return null;
  }
}

function deny(reason) {
  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "deny",
        permissionDecisionReason:
          reason +
          "\n\nIf this is genuinely required, ask the user to run it themselves — this repo's " +
          "safety rules require explicit human authorization for hook-skipping and force-pushes to main.",
      },
    })
  );
}
