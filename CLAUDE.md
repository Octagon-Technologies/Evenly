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
- **Hooks in `.claude/settings.json`** are the enforcement layer, not prose: `em-dash-guard` (Kotlin string
  literals) and `kmp-footgun-guard` (JVM-only APIs in commonMain) run on Write/Edit; `block-risky-git`
  (`--no-verify`/`--no-gpg-sign`/force-push to main), `schema-before-entity` (Room `@Entity` staged without
  `supabase/schema.sql`), `secret-scan`, `pre-commit-lint` (ktlint auto-fix + detekt baseline check), and the
  non-blocking AGENTS.md-sync nudge all run on `git` commands. See `AI_WORKFLOW_PLAYBOOK.md` §2 for the
  rationale behind each. `detekt`'s pre-existing debt is grandfathered in `.claude/config/detekt-baseline.xml`
  — only NEW findings block a commit.
- **Skills:** run `ux-firsttimer` before finishing any UI change (build-time mode), not only when asked to
  audit. `kmp-parity-review` walks a commonMain diff for cross-target compile/behavior parity — run it
  alongside `ux-firsttimer` gating, or before committing any `expect`/`actual` change. `android-cli` handles
  Android SDK and emulator chores.
- **Six review personas** in `.claude/agents/` (`spec-conformance`, `functional-correctness`,
  `idiom-standards`, `edge-cases`, `system-design`, `security-data-safety`) decompose "is this correct" per
  AI_WORKFLOW_PLAYBOOK.md §4 — opt-in via the `Agent` tool or a `Workflow`, not automatic on every diff.
- **Do not spawn an `Explore` or general-purpose agent for a lookup you can grep** — see §3. Reserve
  subagents for genuinely open-ended, cross-cutting questions.
