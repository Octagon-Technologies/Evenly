#!/usr/bin/env node
/**
 * schema-before-entity — on `git commit`, asks for confirmation if staged files touch a
 * Room @Entity (data/db/entity/**) but supabase/schema.sql isn't staged in the same
 * commit. Enforces AGENTS.md non-negotiable #4 (a new column on a synced entity goes
 * server-side first) mechanically instead of hoping it's remembered.
 *
 * This repo applies schema changes directly (via the Supabase MCP tools) and tracks the
 * result in supabase/schema.sql — supabase/migrations/ is unused, so that's the file this
 * checks against, not the migrations folder.
 */

const { execSync } = require("child_process");

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
  if (/--no-verify\b/.test(cmd)) process.exit(0); // block-risky-git.js already denies this

  const cwd = process.env.CLAUDE_PROJECT_DIR || payload.cwd || ".";

  let staged;
  try {
    staged = execSync("git diff --cached --name-only", { cwd, encoding: "utf8" })
      .split("\n")
      .filter(Boolean);
  } catch {
    process.exit(0); // not a git repo, or nothing staged — don't block on our own failure
  }

  const touchesEntity = staged.some((f) => /\/data\/db\/entity\/.*Entity\.kt$/.test(f));
  const touchesSchema = staged.some((f) => /(^|\/)supabase\/schema\.sql$/.test(f));

  if (touchesEntity && !touchesSchema) {
    const entityFiles = staged.filter((f) => /\/data\/db\/entity\/.*Entity\.kt$/.test(f));
    process.stdout.write(
      JSON.stringify({
        hookSpecificOutput: {
          hookEventName: "PreToolUse",
          permissionDecision: "ask",
          permissionDecisionReason:
            `Room @Entity file(s) staged without supabase/schema.sql:\n` +
            entityFiles.map((f) => `  • ${f}`).join("\n") +
            `\n\nAGENTS.md non-negotiable #4: a new column on a synced entity goes server-side ` +
            `first — the full-row upsert sends every field, so a column the server lacks breaks ` +
            `sync for that whole table. If this entity change has no server-side counterpart ` +
            `(e.g. it's a local-only field, or the schema change already shipped in a prior ` +
            `commit), proceed. Otherwise apply the schema change and stage schema.sql first.`,
        },
      })
    );
    return;
  }

  process.exit(0);
});
