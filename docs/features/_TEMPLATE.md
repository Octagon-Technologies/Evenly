# <Feature name>

**Status:** not started | in progress (`<branch>`) | shipped (`<commit>`)
**Owner agent/session:** <who is accountable for the final diff>
**Mockup:** `design/<file>.html` (required for any new or changed screen — AGENTS.md §7)

---

## 1. Behavior brief

**Problem.** What is broken or missing today, from the user's side. Not the solution.

**User outcome.** One sentence: what can a user do when this is done that they cannot do now?

**Non-goals.** What this explicitly does *not* change. Be specific — this is the line a reviewer
holds the diff against when checking for scope creep.

**Platforms and systems affected.** Android / iOS / `supabase/` / `web/` / `admin/`. Name the
layers too (`domain/`, `data/`, `ui/`, `platform/`) so the right nested `AGENTS.md` gets read.

---

## 2. Acceptance criteria

Observable, given/when/then. Every line here must be provable by something in §4.

- Given ... when ... then ...
- Given ... when ... then ...

**States that are not the happy path.** These are criteria, not afterthoughts:

| State | Expected behavior |
| --- | --- |
| Empty (no data yet) | |
| Loading | |
| Error (server rejected) | |
| Offline / queued | |
| Permission denied | |
| Stale local vs newer server row | |

Never a silent dead end: a control that cannot act stays live and explains what is missing on tap
(AGENTS.md §7).

---

## 3. Constraints

- **Data model.** New or changed columns on a synced entity go **server-side first** — migration in
  `supabase/` before or with the Room entity (AGENTS.md §4.4). Deletions are soft (§4.5).
- **Realtime.** Do not add tables to the `supabase_realtime` publication (§4.3).
- **Compatibility.** What happens to users on the current build, and to rows written by them?
- **Privacy / auth.** PII touched, RLS policies involved.
- **Rollout / rollback.** Flag? Migration reversible? What is the undo?

---

## 4. Test plan

Map each acceptance criterion to the cheapest proof that actually catches a regression.

| Criterion | Proof | Where |
| --- | --- | --- |
| Money math, split, balances | Unit test | `commonTest` |
| Repository / merge / mapping | Unit test | `commonTest` |
| Schema, RLS, RPC | SQL check against `supabase/schema.sql` | |
| Screen state rendering | Manual run on the iOS sim (AGENTS.md §5) | |
| Layout / visual polish | `ux-firsttimer`, walked cold | |

Green means **both** targets (AGENTS.md §4.1):

```
./gradlew :shared:compileAndroidMain
./gradlew :shared:compileKotlinIosSimulatorArm64
./gradlew :shared:testAndroidHostTest
./gradlew :shared:iosSimulatorArm64Test
```

---

## 5. Scout findings

Filled in *before* implementation, by a read-only pass. Current flow, files and modules affected,
existing patterns to preserve, risks, where the tests live, open questions.

---

## 6. Slices

One vertical happy path first, then the states in §2. Each slice is a commit.

- [ ] Slice 1 —
- [ ] Slice 2 —

---

## 7. Completion report

Filled in at the end, not predicted at the start.

**Verification:** commands run and their actual results.
**Acceptance criteria checked:** which, and how.
**Known gaps / unverified:**
**Discovered but out of scope:** severity, evidence, recommended next action. Do not fold these
into this diff.
