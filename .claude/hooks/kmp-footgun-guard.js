#!/usr/bin/env node
/**
 * kmp-footgun-guard — flags JVM-only APIs written into commonMain, where they compile
 * fine on Android and silently break `compileKotlinIosSimulatorArm64` (AGENTS.md
 * non-negotiable #1; AI_WORKFLOW_PLAYBOOK.md §2). Each entry here is a footgun that has
 * already been hit once — a hook can't forget it, a memory can drift out of context.
 *
 * Fires "ask" (not a hard deny): these patterns are strong signals, not proof — a few
 * are legitimately reachable from commonMain via expect/actual or a Native-safe overload.
 */

const FOOTGUNS = [
  { pattern: /\bRoomDatabase\.clearAllTables\s*\(/, why: "RoomDatabase.clearAllTables() is JVM-only; unsupported on Kotlin/Native." },
  { pattern: /\bSystem\.currentTimeMillis\s*\(/, why: "System.currentTimeMillis() is JVM-only; use kotlinx.datetime Clock.System.now() instead." },
  { pattern: /\bimport\s+java\./, why: "java.* is not available on Kotlin/Native — commonMain can't import it." },
  { pattern: /\bjava\.util\.UUID\b/, why: "java.util.UUID is JVM-only; use kotlin.uuid.Uuid or a KMP-safe generator." },
  { pattern: /\bThread\s*\(/, why: "java.lang.Thread is JVM-only; use coroutines for concurrency in commonMain." },
  { pattern: /\bsynchronized\s*\(/, why: "synchronized(...) is a JVM-only construct; use a Mutex from kotlinx.coroutines.sync." },
  { pattern: /\bRuntime\.getRuntime\s*\(/, why: "java.lang.Runtime is JVM-only." },
  { pattern: /\bGlobalContext\b/, why: "Koin's GlobalContext is not available on Kotlin/Native (see project memory: sharecost-kmp-build-gotchas)." },
  { pattern: /\bString\.format\s*\(/, why: "String.format(...) relies on java.util.Formatter and is unreliable on Native; build the string manually or use kotlinx patterns." },
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

  const tool = payload.tool_name || "";
  if (!["Write", "Edit", "MultiEdit"].includes(tool)) process.exit(0);

  const ti = payload.tool_input || {};
  const file = ti.file_path || "";

  if (!/\.kt$/.test(file)) process.exit(0);
  if (!/\/commonMain\//.test(file)) process.exit(0); // androidMain/iosMain are platform-specific by design

  const chunks = [];
  if (typeof ti.content === "string") chunks.push(ti.content);
  if (typeof ti.new_string === "string") chunks.push(ti.new_string);
  if (Array.isArray(ti.edits)) {
    for (const e of ti.edits) {
      if (e && typeof e.new_string === "string") chunks.push(e.new_string);
    }
  }
  const text = chunks.join("\n");
  if (!text) process.exit(0);

  const hits = [];
  for (const fg of FOOTGUNS) {
    if (fg.pattern.test(text)) hits.push(fg.why);
  }
  if (hits.length === 0) process.exit(0);

  const reason =
    `Possible KMP footgun in ${file} (commonMain, must compile on both targets):\n` +
    hits.map((h) => `  • ${h}`).join("\n") +
    `\n\nIf this usage is actually Native-safe (e.g. behind expect/actual), proceed. ` +
    `Otherwise fix it now — Android will build green and hide the break until iOS is compiled.`;

  process.stdout.write(
    JSON.stringify({
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "ask",
        permissionDecisionReason: reason,
      },
    })
  );
});
