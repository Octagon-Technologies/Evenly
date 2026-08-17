# Base review — domain & data layers

**Owner-facing.** This file is the plan; you do not hand it to a review session. The four
`BRIEF-*.md` files next to it are what you hand over, one per session.

Written 2026-08-15 against `feat/pro-passes` @ `daef743`.

---

## Why these two layers and not the whole codebase

Reviewing 45k lines costs the same whether or not the findings matter. Scope by **blast radius**
instead — what does a bug here cost, and is it recoverable?

| Layer | commonMain lines | Worst case | Reviewed? |
| --- | --- | --- | --- |
| `domain/` | 2,851 | Wrong balances, silently, for months | **Yes — 1 session** |
| `data/` | 10,703 | Data loss, resurrection, cross-account writes | **Yes — 3 sessions** |
| `ui/` | ~22,000 | A confusing screen. Patch release | **No** — `ux-firsttimer` owns it |
| `platform/` | ~2,900 | Compile/parity breaks | **No** — the iOS build catches these |

The budget works *because* the UI — your single largest layer — is deliberately excluded. That is the
decision that makes this affordable, so don't quietly re-add it.

---

## Can it be parallelized? Yes, completely.

**These sessions do not write code.** They read, verify, and produce a findings file. That removes
every reason parallel sessions normally collide:

- No merge conflicts, because nothing in `code/` is edited.
- No red-build races, no KSP cache thrash — no session runs a build that mutates shared output.
- Each session writes to **its own** findings file. No shared file, no append races.

So run all four at once if you want. If you'd rather stagger, do **domain first** — it is the smallest,
and its findings sharpen the data review (a repository bug is often a domain invariant being violated
one layer up).

The one hard rule, stated in every brief: **a review session never fixes anything.** Fixes are a
separate pass, on a branch, one commit per fix. Mixing them is how parallel sessions start colliding
and how a bad finding gets silently committed.

---

## The four sessions

| Brief | Scope | Lines | Why it's its own session |
| --- | --- | --- | --- |
| `BRIEF-domain.md` | `domain/**` | 2,851 | Pure logic, no I/O. Invariants are provable. Small enough to read exhaustively |
| `BRIEF-data-sync.md` | `data/remote/**`, `data/auth/**` | 2,237 | Sync + session. Highest-risk code in the repo |
| `BRIEF-data-repository.md` | `data/repository/**`, `data/claim/**` | 3,733 | Where money meets persistence. Transaction boundaries |
| `BRIEF-data-db.md` | `data/db/**`, `data/upload/**` | 4,733 | Room ↔ `schema.sql` agreement. Largest, most mechanical |

`data/db/` is the biggest but the least judgment-heavy — much of it is entity/DAO/schema
correspondence, which is checkable rather than arguable. If you only have appetite for three sessions,
that's the one to defer.

---

## How to launch one

Open a fresh Claude Code session in the repo and give it exactly this, substituting the brief:

```
Read review/BRIEF-domain.md and carry it out end to end. Follow its output contract
exactly. Do not fix anything you find.
```

Nothing else. The brief carries its own scope, method, exclusions and output format precisely so the
kickoff prompt stays that short — anything you add in chat competes with it for attention.

**Use a high reasoning effort.** For this kind of work, depth per session beats more sessions: a
shallow reviewer produces plausible findings you then have to disprove yourself, which costs you more
than it saved.

---

## What you get back

Each session writes `review/findings-<scope>.md` in the format its brief specifies. Every finding
carries a concrete failing input, a severity, and a verdict from an adversarial self-check pass.

**Then baseline it.** Once you've triaged the four files, record the accepted state the way
`.claude/config/detekt-baseline.xml` grandfathers detekt debt. That is the point of the whole
exercise: after this, "review the diff only" stops being an assumption you can't justify and becomes
a fact. You pay for the base review once.

---

## What this deliberately does not cover

Say these out loud rather than discovering them later:

- **The UI layer**, per the table above.
- **The 14 findings in `ARCH_SECURITY_REVIEW.md`** — already known, already triaged in
  `SECURITY_FIX_HANDOFF.md`. Every brief excludes them explicitly so no session burns budget
  rediscovering them.
- **The known P0s** — permissive RLS, destructive Room migration. Known, tracked, not findings.
- **Anything only execution can prove** — the purchase path, real multi-device sync, gateway limits at
  scale. No amount of reading substitutes; see the RevenueCat Test Store route for the first one.

A review that rediscovers what you already wrote down is theater. Every brief starts with the
exclusion list for that reason.
