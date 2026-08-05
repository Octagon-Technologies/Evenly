@AGENTS.md

## Claude Code specifics

The briefing above is the shared, cross-tool one (`AGENTS.md`, also read by Codex, Cursor, Copilot and
others). These notes apply only to Claude Code.

- **Nested briefings load on demand.** Every directory in the §2 table has a `CLAUDE.md` containing
  `@AGENTS.md`, so that area's rules enter context when you read a file there. Nested files are **not**
  re-injected after `/compact` — if a long session drifts on an area rule, re-read that directory's
  `AGENTS.md` rather than assuming it is still in context.
- **`.claude/rules/kmp-source-sets.md`** covers the gap nesting cannot: it fires on `androidMain` and
  `iosMain` paths and points at the owning layer's briefing.
- **Hooks in `.claude/settings.json`** are the enforcement layer, not prose: `em-dash-guard` on Kotlin string
  literals, and a non-blocking convention nudge at `git commit`.
- **Skills:** run `ux-firsttimer` before finishing any UI change (build-time mode), not only when asked to
  audit. `android-cli` handles Android SDK and emulator chores.
- **Do not spawn an `Explore` or general-purpose agent for a lookup you can grep** — see §3. Reserve
  subagents for genuinely open-ended, cross-cutting questions.
