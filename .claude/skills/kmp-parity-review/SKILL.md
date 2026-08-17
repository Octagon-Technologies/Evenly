---
name: kmp-parity-review
description: >-
  Walk a diff and check it will actually compile and behave the same on both
  Kotlin/Native (iOS) and JVM (Android) targets — not just Android, which is
  what "it compiles" silently means if you never run the iOS check. Use after
  any change that touches commonMain, adds or edits an expect/actual pair, or
  touches Room/Compose/coroutines usage in shared code. Triggers: "check KMP
  parity", "will this compile on iOS", "review this expect/actual", "does this
  work cross-platform", or any commonMain diff before commit.
---

# kmp-parity-review — does this diff actually work on both targets?

## Why this exists

AGENTS.md non-negotiable #1: **Android-green ≠ iOS-green.** Several APIs resolve on
Android/JVM and fail (or silently no-op, or crash at runtime) on Kotlin/Native. A generic
code reviewer without KMP experience has no reason to know this — it's exactly the kind of
check a narrow, repeatable pass catches and a broad one misses. `kmp-footgun-guard.js`
(`.claude/hooks/`) catches a fixed list of known JVM-only patterns *as they're typed*; this
skill is the deeper, judgment pass over a whole diff that the hook can't do — it can't tell
whether an `expect`/`actual` pair stays behaviorally symmetric, or whether a "works on Native"
API produces different results on each platform.

## What this skill is not

Not a substitute for actually compiling. This is a fast, cheap pre-check to catch what a human
reviewer (or a generalist review pass) would miss before spending a build cycle on it. Still run
the real compiles from AGENTS.md §5 before calling anything green:

```bash
./gradlew :shared:compileAndroidMain
./gradlew :shared:compileKotlinIosSimulatorArm64
```

## How to run this review

1. Get the diff: `git diff` (uncommitted) or `git diff <base>...<head>` (a branch/PR).
2. Restrict attention to `commonMain` files, `expect`/`actual` pairs anywhere in the diff, and
   any Room/Compose/coroutines usage. Files under `androidMain`/`iosMain` alone are fine to skim
   (platform-specific code is *supposed* to use platform APIs) — the risk is `commonMain` code
   that assumes JVM/Native symmetry it doesn't have.
3. For each commonMain change, answer these four questions explicitly. Don't just eyeball it —
   write the answer, even if it's "yes, fine."

### Q1 — Does every API used in commonMain actually exist on Kotlin/Native?

Known-bad patterns already caught by the hook (don't re-derive these, just confirm none slipped
past it): `RoomDatabase.clearAllTables()`, `System.currentTimeMillis()`, any `java.*` import,
`java.util.UUID`, `Thread(...)`, `synchronized(...)`, `Runtime.getRuntime()`, Koin's
`GlobalContext`, `String.format(...)`.

Beyond the hook's fixed list, check for anything **new** to this diff that isn't obviously
common-stdlib — a third-party library call, a less common `kotlinx.*` API, reflection, or
resource/file access. If you're not certain an API is Native-safe, say so and flag it rather
than assuming.

### Q2 — Does every `expect` declaration have a matching `actual` on both platforms?

Search the diff (and the surrounding files if the diff only touches one side) for `expect` and
`actual` keywords. An `expect` with only one `actual` compiles cleanly on the platform that has
it and fails to compile on the platform that doesn't — Android's build will not catch a missing
iOS `actual`, only `compileKotlinIosSimulatorArm64` will.

```bash
grep -rn "^expect " code/shared/src/commonMain
grep -rn "^actual " code/shared/src/androidMain code/shared/src/iosMain
```

### Q3 — Do the `actual` implementations behave the same, not just compile the same?

Two `actual`s that both compile can still diverge in observable behavior — different rounding,
different null handling, different thread/dispatcher defaults, a platform-specific edge case in
one that's silently absent in the other (e.g. an Android `actual` that clamps a value and an iOS
`actual` that doesn't). Read both implementations side by side; don't assume symmetry from the
shared `expect` signature alone.

### Q4 — Is anything in this diff a *new* JVM-only footgun the hook doesn't know about yet?

If you find a pattern here that isn't in the hook's list and it's a genuine "compiles on Android,
breaks on Native" trap, add it to `FOOTGUNS` in `.claude/hooks/kmp-footgun-guard.js` in the same
commit — a hook can't forget it once it's there, and the next diff won't need this manual check
to catch it again.

## Reporting

Report per-file, one line per question that has a real finding (skip questions with nothing to
say — don't pad the report). For each finding: file:line, which question it answers, and whether
it's a compile-breaker (must fix before commit) or a behavior-divergence (needs a decision, not
necessarily a blocker). End with a one-line verdict: **clear to compile-check**, or **fix N items
first**.
