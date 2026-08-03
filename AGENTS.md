# Evenly — Agent Briefing

Read at the start of every session. **This file is a router, not an encyclopedia.** It holds only
what applies to *every* session; everything narrower lives one level down and loads on demand.

Evenly is a **mobile-only** expense-splitting app: friends share a group, log expenses, split them
(evenly, by share, or itemized off a receipt), and settle up. It is a Kotlin Multiplatform app
targeting **Android and iOS only** — there is no web, desktop, or server-rendered surface, and
`:shared` carries the Compose UI as well as the logic. Two OSes are the whole product surface, which
is why "it compiles" is never the bar (see §4).

## 1. Where knowledge lives

Do not guess, and do not ask the owner for anything in this table. Go read it.

| Question                                | Source                                            |
| --------------------------------------- | ------------------------------------------------- |
| What is the product supposed to do?     | `App_Overview.md`                                 |
| What does a screen look like?           | `design/` — the **visual source of truth**        |
| What's built, what's left?              | `FINISH_PLAN.md`, `REMAINING_WORK.md` (history: `BUILD_PLAN.md`) |
| Gotchas for the area I'm editing        | The nearest `AGENTS.md` (see §2)                  |
| Database shape, RLS, RPCs               | `supabase/schema.sql` + `supabase/AGENTS.md`      |
| How do I run it on a device?            | §5 below                                          |
| Why does this code look like this?      | `git log -p` on the file, then the nearest `AGENTS.md` |

**Precedence when sources disagree:** the nearest `AGENTS.md` > this file > `design/` > the planning
docs. `design/` is deliberately blue/light and **overrides** the older spec's dark/green — do not
"fix" the UI back toward the spec. A disagreement between `supabase/schema.sql` and a Room entity is
a **P0**: stop and reconcile before writing code on top of it.

## 2. Nested AGENTS.md — read the one for your area

Paths below are under `code/shared/src/commonMain/kotlin/app/splitevenly/` unless noted.

| Working in                                    | Read                     |
| --------------------------------------------- | ------------------------ |
| `data/**` — Room, sync, repositories, merge   | `data/AGENTS.md`         |
| `domain/**` — money math, split, balances     | `domain/AGENTS.md`       |
| `ui/**` — Compose screens, nav, copy          | `ui/AGENTS.md`           |
| `platform/**` — `expect`/`actual`, permissions | `platform/AGENTS.md`     |
| `supabase/**` — schema, RLS, edge functions   | `supabase/AGENTS.md`     |
| `code/iosApp/**` — Xcode host, Kotlin/Native  | `code/iosApp/AGENTS.md`  |
| `web/**` — the web claim surface + TS money port | `web/AGENTS.md`       |

If you are about to touch one of those trees and have not read its file, read it first. It exists
because someone already made the mistake you are about to make.

**KMP note:** `androidMain` and `iosMain` hold the `actual`s for `commonMain` interfaces. The rules
for an `actual` live in the *layer's* file above (mostly `platform/AGENTS.md`), not in a source-set
file. `.claude/rules/kmp-source-sets.md` routes this automatically for Claude.

## 3. Reach for the right mechanism

| Situation                                          | Use                                | Why                                        |
| -------------------------------------------------- | ---------------------------------- | ------------------------------------------ |
| Finding a screen/string/symbol you can name        | `grep -rn "exact text" code/shared/src` | One call, near-zero tokens             |
| Genuinely open-ended "how does X work across the app" | `Explore` subagent              | Keeps the main context clean               |
| Any new or changed screen or flow                  | `ux-firsttimer` skill              | Build-time gate, not just an audit tool    |
| Something must happen every time, no exceptions    | A hook in `.claude/settings.json`  | Prose is advisory; hooks are deterministic |

**Never open a search agent for a lookup you can grep.** A screenshot or a description of a UI element
("the card with the camera icon") is not a search query — the *visible text rendered in it* is. Take
the literal string and grep it. An agent asked to search by visual description reads whole files to
compensate; that is how a single-file lookup costs 30k tokens.

**The rule that decides where a new rule goes:** if a script can determine the answer, it belongs in a
hook, not in this file. If it needs judgment and is tied to a *place*, it belongs in that place's
`AGENTS.md`. If it is tied to a *task*, it belongs in a skill. Only project-wide standing context
belongs here.

## 4. Non-negotiables

Violating any of these is a defect regardless of what the task asked for.

1. **Both platforms compile.** Android-green ≠ iOS-green. Several APIs resolve on Android/JVM and fail
   on Kotlin/Native (e.g. `RoomDatabase.clearAllTables()`). Never declare green without §5's iOS
   compile.
2. **"Done" means it ran.** After any app-facing change, launch it on a simulator and leave it running
   so the owner can pick it up and check. A green build that was never run does not count as done here.
3. **Never re-add app tables to the `supabase_realtime` publication.** Realtime is a one-table
   doorbell. Publishing all tables once fanned out one message per row per client and burned 13.9M
   messages against a 5M quota. See `supabase/AGENTS.md`.
4. **A new column on a synced entity goes server-side first.** The full-row upsert sends every field,
   so a column the server lacks breaks *all* sync for that table. Migration before/with the entity
   change.
5. **Soft-delete user data. Never hard-delete, `DROP`, or `TRUNCATE`.** Tombstones must reach the
   server or the deletion resurrects on the next pull. Full rules in `data/AGENTS.md`.
6. **Never commit secrets.** `local.properties` is gitignored. `google-services.json` and the Supabase
   *anon* key are intentionally tracked (public, RLS-gated). Never commit service-account JSON, APNs
   `.p8`/`.p12`, or a private `GoogleService-Info.plist`. Check `git diff --cached --name-only` first.
7. **No em dashes in user-facing strings.** Compose copy only, not comments or docs. Enforced by the
   `em-dash-guard` hook; see `ui/AGENTS.md` for the copy rules and the escape hatch.
8. **The app is *Evenly*; the identifiers are `app.splitevenly`.** Product name, bundle/applicationId,
   `splitevenly://` deep links, and `split-evenly.app` links all move together or not at all. Brand blue
   `#3762E3` is declared in **three** places that cannot read each other (`EvenlySplashBlue`,
   `values/colors.xml`, iOS `LaunchBackground.colorset`); change one and you must change all three or the
   launch flickers. The feather is traced from `design/logo-inspo/`, single-source in `EvWordmark.kt`.

## 5. Build & verify

`gradlew` lives in **`code/`**, not the repo root. JDK 17. Run from `code/`:

```bash
./gradlew :shared:compileAndroidMain            # shared, Android target (commonMain + androidMain + Room KSP)
./gradlew :shared:compileKotlinIosSimulatorArm64 # shared, Kotlin/Native — catches Native-only breakage
./gradlew :androidApp:assembleDebug              # Android APK (manifest merge, Firebase)
./gradlew :shared:testAndroidHostTest            # JVM unit tests (commonTest + androidHostTest)
./gradlew :shared:iosSimulatorArm64Test          # Native unit tests (commonTest + iosTest)
```

**Manual verification defaults to the iOS simulator** when either platform would do — the owner
usually has it open, and running a simulator and an emulator at once burns CPU/RAM for nothing:

```bash
code/iosApp/run-ios-sim.sh
```

It mirrors the Android Studio "iOS App (Simulator)" run config: boots or reuses a sim, builds via
`xcodebuild` (which also compiles the shared Kotlin/Native framework), then installs and launches.
Reach for the Android emulator only when the change is Android-specific (`androidMain`,
Compose-on-Android quirks, manifest/Firebase) or the owner asks for Android.

Toolchain anchor **Kotlin 2.3.21** (pinned by supabase-kt 3.6.0 / Ktor 3.4.3). minSdk 24,
compile/target 36, iOS 16, Compose MP 1.11.0, Room 2.8.4.

## 6. Commit rules

**Commit per feature, and only when the build is green.** The point: lock in the verified ~80% so a
failure in the last 10% never threatens work that already passed.

1. **Verify before every commit** — build + tests for *both* platforms (§5), confirmed green. Never
   commit on a red build. If you cannot verify, say so; do not commit blind.
2. **One coherent feature per commit.** Use a selective `git add <paths>` if unrelated files are in
   the tree. Do not batch unrelated features.
3. **Branch, don't commit to `main`.** If you are on `main`, branch first (`feat/...`).
4. **Conventional messages with a scope:** `feat(sync): …`, `fix(bill): …`, `docs(ios): …`. Body says
   *what changed and why*, plus a "Green on Android + iOS; tests pass" line when true.
5. **Every commit ends with the Claude `Co-Authored-By:` trailer.**
6. **Don't silently defer.** If something cannot be finished, name what is blocked and why. Do not
   dress up caution as a hard constraint.
7. **Keep agent docs in sync — enforced.** When a commit changes a build, test, commit, or
   architecture convention, update the relevant `AGENTS.md` *in that same commit*. A `PreToolUse` hook
   nudges at commit time; it is non-blocking, so ignore it when nothing convention-level changed.

## 7. Working style

- **Mock UI before you build it.** For any new screen or non-trivial UI change, produce a faithful
  visual mockup for the owner *before* writing Compose. This has repeatedly surfaced simplifications
  that would have been costly to find in code. Design decisions get made in the mock, not the PR. For
  big features, agree the data/logic model first too.
- **Run `ux-firsttimer` before any UI change is "done."** Build-time mode, walked cold as the
  low-patience persona (Sam the invited friend, Diego the dinner claimer). Clear every P0/P1. The bar
  is "a confused friend could do this unaided," not "compiles and runs."
- **Never leave a silent dead end.** Keep action buttons live and validate on tap, showing what is
  missing. Do not grey out a control with no explanation.
- **Verify claims before asserting them.** If you state a line number, a path, or a count, run the
  command that proves it. A doc that is confidently wrong is worse than one that is silent.

## 8. Maintaining this file

- **Ceiling: 170 lines.** It currently sits at 169. Something must leave before something enters. Do
  not raise the number — a ceiling with room to spare is not a constraint.
- **The test for a new line:** would removing this cause a mistake in a session that is **not** about
  this feature? If no, it belongs in a nested `AGENTS.md`, a skill, or a hook.
- **Never append a bug postmortem here.** Put the generalizable rule in the nested file, the fix in a
  test, and the behavior in the spec.
- **Delete superseded lines in place.** Appending a correction while the wrong sentence survives above
  it is worse than never documenting it.
- **Dated phase logs do not belong here.** History lives in git.
