# AI-Native Android Workflow Playbook

Personal reference for how to use Claude Code at the level described in the Grove "AI-First
Android Engineer" posting — orchestrating agents through spec, codegen, review, and test, with
your judgment as the bottleneck instead of your typing speed. Written against this repo
(Evenly/ShareCost, KMP, Android + iOS) but the structure applies to any project.

---

## 1. The mental model: three enforcement layers

Don't ask Claude to "review carefully" and hope. Separate concerns by how reliable the check
needs to be:

| Layer | Mechanism | Use for |
|---|---|---|
| **Deterministic** | Hooks | Things that must *never* pass, no exceptions, no judgment involved |
| **Judgment, single-pass** | Skills / slash commands | Repeatable playbooks where one competent pass is enough |
| **Judgment, adversarial/multi-facet** | Subagents + Workflow | Correctness questions with many independent failure modes, where one pass misses what a second angle catches |

Hooks shrink the space judgment has to cover. Skills make judgment repeatable instead of
reinvented per session. Subagents decompose "is this correct" into dimensions that don't
interfere with each other. Set them up in that order — hooks first, since they're free and
infallible.

---

## 2. Hooks — what should never require a human or an LLM to catch

Already in this repo: `em-dash-guard.js` (blocks em dashes in Compose strings), a commit-time
nudge to keep `AGENTS.md` in sync.

Worth adding, in priority order:

- **Block risky git ops from being auto-approved** — `--no-verify`, `--no-gpg-sign`, force-push
  to `main`. (Partially covered by session-level safety rules; codifying it in a hook means it
  can't be talked around mid-session.)
- **KMP-footgun grep on `Write`/`Edit`** — flag JVM-only APIs that silently break
  `compileKotlinIosSimulatorArm64` (e.g. `RoomDatabase.clearAllTables()`). Every time you hit one
  of these "compiles on Android, dies on iOS" traps for the first time, that's a permanent hook
  rule, not a memory — a hook can't forget, a memory can drift out of context.
- **Schema-before-entity check on `git commit`** — block (or warn) if staged files touch a Room
  `@Entity` without a matching file under `supabase/migrations/`. Enforces non-negotiable #4 in
  `AGENTS.md` mechanically instead of hoping the agent remembers it.
- **Secret-pattern warning on `git add`** — flag if staged files include changed
  `google-services.json` / `GoogleService-Info.plist` or anything matching common secret
  patterns.
- **Lint-on-save** — run `ktlint --format` / `detekt` on the touched `.kt`/`.kts` file after every
  Write/Edit. Style and static-analysis correctness shouldn't cost an LLM judgment call at all.

**The test for "should this be a hook":** would I be upset if this slipped through even once? If
yes, it's not advisory — it's a hook.

---

## 3. Skills — the repeatable playbooks

Built-ins already in use: `code-review`, `testing-strategy`, `architecture`, and this project's
own `ux-firsttimer` (build-time UX gate, not just an audit tool).

Worth adding:

- **`kmp-parity-review`** — a project skill that walks a diff and explicitly answers: does this
  compile on both targets, does the `expect`/`actual` pair stay behaviorally symmetric, did a
  Room/Compose/coroutines API get used that's JVM-only. Highest-value custom skill for this repo,
  because it's exactly what a generic reviewer without KMP experience won't know to check.
- **Spec-first via Plan mode** — write the spec as a markdown file first (as already done for
  `PRO_PASS_SPEC.md`), then use Plan mode with the spec as input so Claude proposes an
  implementation plan *before* writing code. The spec is the contract; the plan is the agent's
  proposed fulfillment of it. Gate approval at the plan stage, where changes are cheap.

---

## 4. Personas (subagents) — decomposing "correctness"

"Correctness" isn't one check — it's several independent axes, and a single reviewer pass tends
to anchor on whichever axis it noticed first and under-check the rest. Fix: one narrow persona
per axis, run in parallel, each blind to the others' findings.

| Persona | Checks | Explicitly ignores |
|---|---|---|
| **Spec conformance** | Diff against the spec doc — does behavior match what was asked, any silent scope changes | Style, perf |
| **Functional correctness** | Does the logic actually do what it claims — trace the money math, split logic, sync merge | Spec intent |
| **Idiom/standards** | Kotlin/Compose conventions, structured-concurrency misuse, recomposition footguns | Business logic |
| **Edge cases** | Null/empty/huge inputs, offline, concurrent writers, rotation/process death, platform divergence | Style |
| **System design** | Module/layer boundaries, does this belong in `domain/` vs `data/`, layering-rule violations | Line-level bugs |
| **Security / data safety** | Secrets, RLS gaps, hard-deletes where soft-delete is required, PII in logs | Everything else |

Each is a narrow `.claude/agents/*.md` subagent — narrow scope is what makes each pass reliable.
A generalist reviewer's attention dilutes across all axes and does none of them well.

---

## 5. Running the multi-facet review — efficient, scalable, human stays the judge

Shape (this is literally the canonical `Workflow` tool pattern — dimension-based review with
adversarial verification):

```
pipeline(
  [spec, functional, idiom, edge-cases, design, security],
  dim => agent(dim.prompt, {phase: 'Review', schema: FINDINGS}),
  findings => parallel(findings.map(f =>
    agent(`Try to refute this finding: ${f}`, {phase: 'Verify', schema: VERDICT})
  ))
)
```

Why this is efficient *and* scalable:

- **Parallel, not sequential** — six reviewers run concurrently, not one agent doing six passes
  serially (slower, and worse — context from pass 1 biases pass 2).
- **Adversarial verify kills false positives before they reach you** — a flagged "security" issue
  that isn't real gets refuted before it hits your queue. This is what keeps you in the loop
  without drowning you: a short, high-precision list, not six raw dumps.
- **You stay the judge, not a filter** — the system's job is to push noise below the threshold
  where your judgment is the bottleneck, not to replace it. CONFIRMED findings still need you to
  decide: fix now, track as debt, or reject as wrong.

**This is opt-in, not automatic.** Full multi-agent orchestration only runs when explicitly
invoked (naming a workflow, "ultracode", or the built-in `/code-review high`/`ultra`, which
already does dimension-based review with verification). Use `/code-review high` for day-to-day
PRs; reserve the full custom six-persona workflow for high-stakes changes — money math, sync
merge logic, purchase flows.

### The loop, per PR

1. Spec written → Plan mode proposes the implementation approach → you approve or redirect
   *(judgment checkpoint 1)*
2. Agent implements
3. Hooks catch the deterministic stuff automatically — no judgment spent
4. `/code-review high` (or the full dimension workflow for high-stakes changes) → adversarially
   verified, short finding list
5. You triage: fix now / defer with a tracked reason / reject the finding as wrong *(judgment
   checkpoint 2 — worth narrating in a portfolio story; "I overrode the agent here and here's
   why" is the actual taste signal)*
6. `testing-strategy` skill or a dedicated test-writing pass; tests run in CI
7. Merge

---

## 6. Spec vs. Plan

**Spec = the contract for *what* and *why*.** Required behavior, constraints, and the reasoning
behind decisions, in plain language, independent of any particular implementation. It should stay
true even if the codebase were rewritten from scratch.

**Plan = the agent's proposal for *how*.** Given a spec (or a smaller ask), a plan is "these
files, in this order, here's the tricky part, here's what I'm uncertain about." Implementation-
shaped and short-lived — it only makes sense against the current codebase.

### Who writes each

- **Spec: primarily you, with the agent as a drafting/formatting assistant, not the author of the
  decisions.** You supply the goal, the constraints, the why. The agent can turn bullets into a
  structured document, ask clarifying questions, flag ambiguity — but the policy decisions
  (pricing, what counts as abuse, what the failure mode should be) have to be yours, reviewed and
  owned by you before the doc is final. A spec the agent invented from nothing just reflects the
  agent's assumptions, not your product judgment.
- **Plan: primarily the agent, always reviewed by you before code gets written.** This is the
  literal purpose of Plan mode — the agent proposes, you approve or redirect, then implementation
  starts.

`PRO_PASS_SPEC.md` is the reference example of the spec side in this repo: states the *what* (two
products, their prices, their behaviors), the *why* (the 1-star-review failure mode that killed
the subscription-only design), and a revision history kept in place rather than a scattered
changelog.

### Writing a spec — what to include

- **Problem/motivation** — why this, why now. Without it, nobody can tell later whether a
  constraint is load-bearing or arbitrary.
- **In scope / explicitly out of scope** — non-goals matter as much as goals; they're what stop
  the work from quietly expanding.
- **User-facing behavior, described concretely** — "shows X when Y," not "handles the flow."
  Behavior, not implementation.
- **Edge cases and failure modes, enumerated** — not left implicit. Unwritten edge cases surface
  as bug reports instead.
- **Decisions with their rationale, especially reversals** — what was tried first and why it
  broke, the way `PRO_PASS_SPEC.md` §13 does. That rationale is what stops the same mistake from
  being proposed again later.
- **Acceptance criteria** — how you'll know it's done. Checkable, not vibes.
- **Open questions, marked as open** — better to flag "not decided yet" than let the agent
  silently assume an answer.

**Checklist before a spec is "ready":** states *why*, not just *what* · edge cases named, not
implied · scope bounded (could someone read it and expand the feature by accident?) · "done" is
checkable · every ambiguity flagged rather than silently resolved.

### Habit loop

Bullets in chat → ask for a spec draft → edit the *why* and constraints sections yourself → Plan
mode to turn the spec into an implementation plan. The Plan-mode entry point is the mechanical
trigger that forces spec-before-code sequencing.

### What requires a spec, and where it's overkill

**Requires one:** a new screen or flow · anything touching money, the data model, or sync (see
`AGENTS.md` non-negotiables #3-5) · anything crossing a system boundary (client + server together)
· anything with an embedded policy decision · anything where the reasoning wouldn't be obvious
from the diff alone.

**Doesn't:** a positioning/copy/color tweak · a refactor with no behavior change · a bug fix with
a known, obvious, local cause.

**The overkill test:** if the entire requirement fits in one or two sentences, with no embedded
policy decision and no *why* worth preserving, skip the spec and just make the edit. A spec nobody
will ever re-read is worse than no spec — it's a stale file someone has to notice is stale.

**Two specs instead of one:** split when the feature has two decision sets with genuinely
independent risk or timelines. Keep it one spec when the decisions are coupled — `PRO_PASS_SPEC.md`
deliberately keeps the subscription and the group pass in one doc because the pass exists
specifically to fix a subscription failure mode; splitting them would hide that coupling. Split by
independence, not by size.

### Revising an existing feature

- **Just edit** when the revision doesn't change any decision or rationale the original spec
  documented — a copy tweak, a bug fix, a threshold changing with no new reasoning behind it.
- **Update the spec in place** when the revision changes a decision the spec made — because after
  the edit, the old spec text would be actively wrong about behavior or rationale, and a wrong doc
  is worse than none. Follow `PRO_PASS_SPEC.md`'s own convention: *"rewritten in place rather than
  corrected in an appendix."* Update in place, don't append a correction while the wrong sentence
  still stands above it.
- **Write a genuinely new, separate spec** only when the revision is a distinct feature layered on
  top — different scope, different audience — not a change to the original's decisions.

Rule of thumb: if someone reading only the original spec would now be misled about what the app
does or why, that spec is stale and the work isn't done until it's updated too.

---

## 7. Prompting by change type

- **Small, single-element UI tweak** — a literal anchor (file, or exact visible text to grep), a
  relation not a vibe ("align trailing edge with the card above," not "move it a bit"), a
  screenshot if you have one. Direct edit, verify on simulator. No spec.

- **UI change confined to one page, still purely visual** — a short bullet list of what should
  differ, anchored to something concrete (an existing screen in `design/` as the target style),
  constraints named explicitly (nav stays, use `c.blueText` not raw blue). If you don't know what
  you want yet, mock first (2-3 quick HTML variants), converge on the mockup, then implement. Still
  no spec — presentation only, not a policy decision.

- **Logic change behind existing UI (behavior differs, nothing visual changes)** — describe
  *current* behavior, *desired* behavior, and the precise trigger condition or edge case that's
  different, not just the end state. Point at the function if known (grep first). State what must
  **not** change — this is the regression guard, since there's no screenshot to eyeball afterward.
  Ask for the verification to be a test, not a visual check.

- **Full feature, frontend to backend** — the full spec → plan → build loop (§6). State the
  problem and the why, a rough scope, and explicitly ask for a spec draft first rather than
  jumping to "build this."

- **Debugging a crash with an unknown cause** — inverts the small-tweak advice: don't pre-guess
  the location. Scoping a bug prompt means scoping the *investigation*, not the *solution*. Give
  the exact symptom (stack trace, crash log, Crashlytics report, or precise repro steps), what's
  already ruled out, and whether it's reproducible or intermittent — then let the agent trace
  outward from that evidence rather than from a hypothesis about which file is at fault. Invoke the
  `engineering:debug` skill (reproduce → isolate → diagnose → fix) explicitly instead of asking for
  "a fix" directly — asking for a fix skips the isolation step and tends to produce a patch for the
  symptom nearest the stack trace rather than the actual cause.

**Annotating a screenshot for a UI prompt:** macOS's built-in markup is enough —
`Cmd+Shift+4` to capture, click the thumbnail before it disappears, use the arrow/circle/text
tools in the Markup editor that opens, then drop the annotated image into chat. An arrow or circle
plus a short label ("too much gap," "wrong side") beats a bare mark or a spatial description —
the image carries the *where*, a line of accompanying text carries the *what should happen
instead*.

---

## 8. Open gaps for this repo (as of 2026-08-13)

- No CI/CD pipeline yet (GitHub Actions running both-platform compile + tests on PR) — biggest
  objective gap against the Grove profile's "About You" list.
- No production observability wired in (Crashlytics or equivalent) — needed for a real
  "noticed it was on fire, fixed it" story.
- `kmp-parity-review` skill and the KMP-footgun/schema-migration hooks described above don't
  exist yet — currently enforced only by prose in `AGENTS.md`.
