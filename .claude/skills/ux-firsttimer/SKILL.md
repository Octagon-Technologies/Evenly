---
name: ux-firsttimer
description: >-
  Evaluate Evenly's UI as a confused first-time user to find friction before
  real people do. Use when auditing or testing usability, deciding whether a
  screen or flow is understandable, or BEFORE finishing ANY UI change. Walks the
  core money-splitting jobs "cold" (no architecture knowledge), scores against
  tap budgets, a jargon blocklist, and 10 heuristics, and reports severity-ranked
  friction with small suggested fixes. Triggers: "audit the UX", "is this
  confusing", "test like a new user", "would a beta tester get this", "review
  this flow", or any new/changed screen.
---

# ux-firsttimer — audit Evenly the way a real user would

## Why this exists

The person who built a screen is the worst judge of whether it's usable — everything
makes sense *because you already know the answer*. This is the **curse of knowledge**, and
it is the single reason Evenly feels intuitive to its author and opaque to a beta tester.

This skill replaces "does this make sense to me?" (useless) with "does this make sense to
**someone who has never seen it and doesn't care how it works**?" (the only question that
matters). Evenly is a *money* app for *non-technical friends*; the bar is not "a smart
person could figure it out" — it's "a mildly-drunk friend at a dinner table gets it in one
tap, and never wonders whether they owe or are owed."

**The two modes:**
- **Audit mode** — walk the whole app cold, produce a ranked findings report. Run before a
  beta round or when asked to test usability.
- **Build-time mode** — before finishing a UI change, walk *that* flow as the relevant
  persona and clear P0/P1. See the checklist at the bottom.

---

## The prime directive: be the persona, not the builder

You (the main agent) have `CLAUDE.md` loaded — you know about CAS, materialization, derived
remaining, placeholders. **That knowledge is contamination for this task.** Two ways to get
genuinely cold eyes, strongest first:

1. **Spawn a cold subagent (preferred for real audits).** Launch an `Explore` or
   `general-purpose` agent and give it ONLY: (a) screenshots of the flow, (b) a one-paragraph
   persona brief, (c) the goal ("you're trying to pay Sam back for dinner"). Do **not** give it
   CLAUDE.md, the architecture, or the answer. Prompt it: *"You have never seen this app. Narrate
   every tap. At each screen, before you act, say what you expect to happen and where your eye
   goes first. Every time you're unsure, stop and say so."* Its confusion is the finding.
2. **Method-act yourself.** If walking it inline, drop all implementation knowledge. React only
   to pixels on screen. The instant you catch yourself thinking "well, that's because the
   `commit_expense` RPC…" — that thought is a bug report: the UI is leaning on knowledge the user
   doesn't have.

---

## The personas (the people who are not you)

Pick the one whose job you're testing. Each has a **patience budget** — exceed it and they bounce.

| Persona | Who | Wants | Patience |
|---|---|---|---|
| **Priya, the organizer** | Created the group, tech-comfortable, added everyone | Log expenses fast, see the group's state | Medium — she'll explore |
| **Sam, the invited friend** | Tapped a link, no account, zero context | "Do I owe or am I owed? How do I pay? Done." | **Very low** — this is your hardest, most important user |
| **Diego, the dinner claimer** | At the table, splitting a bill live, half-distracted | "Tap the two tacos I had, see my number, stop" | **Lowest** — 10 seconds, noisy room |
| **Mara, the settler** | Owes money, wants it off her plate | "Pay Sam the exact amount through Venmo, mark it done" | Low — she wants closure |

If a flow only works for Priya (who has context), it fails. **Sam and Diego are the review.**

---

## Core jobs + tap budgets

Every audit tests these six. A job **fails** if the persona can't complete it unaided, produces
a wrong-money outcome, or blows the tap budget. Budget = taps from the relevant starting screen
to "done," not counting typing.

| # | Job | Persona | Budget | "Done" means |
|---|---|---|---|---|
| 1 | Join a group from an invite | Sam | ≤ 3 | In the group, sees the expense list |
| 2 | Add an even-split expense | Priya | ≤ 6 | Expense saved, visible in list |
| 3 | Split an itemized bill (scan → claim → finish) | Diego | ≤ 8 | My items claimed, my number shown |
| 4 | Answer "do I owe or am I owed, how much?" | Sam | ≤ 1 | Correct direction + amount, glanceable |
| 5 | Settle up & record a payment | Mara | ≤ 5 | Payment recorded, balance drops |
| 6 | Claim a past identity / resolve a conflict | Priya | ≤ 4 | Merged/resolved, no confusion about what happened |

Job 4 is the **heartbeat**. It's the #1 competitor complaint ("this app miss the basics — I
can't tell who owes whom"). If a first-timer ever has to *think* about direction, that's a P0.

Concrete cold-walkthrough scripts for each flow — with the real route/screen names — are in
[references/flows.md](references/flows.md).

---

## The rubric — 10 heuristics with teeth

Score every screen. Each is a yes/no with a concrete test, not a vibe.

1. **Orientation (3-second rule).** Land cold: can you name what screen this is and what it's
   for within 3 seconds? Title present and plain? If you'd have to scroll or read carefully to
   know where you are → fail.
2. **The core question is glanceable.** For anything balance/expense related: is "do I owe or am
   I owed, and how much" answerable *at a glance*, with direction unmistakable (not just a signed
   number)? Color alone is not enough (colorblind + the design bans green). → the Tricount
   attribution failure.
3. **Obvious next action.** Is the primary action the most visually dominant thing, and labeled
   as a **verb the user thinks in** ("Pay Sam back", not "Settle" / "Apply allocation")? One
   primary action per screen; secondary actions visibly subordinate.
4. **No dead ends.** Every empty state, disabled control, and error tells you *what to do next*.
   A greyed button with no reason is a bug (see the guide-when-blocked principle). Empty states
   teach the first action, they don't just say "nothing here."
5. **Plain words.** No internal vocabulary in user-facing text. Run the blocklist in
   [references/anti-patterns.md](references/anti-patterns.md#jargon-blocklist). Labels match the
   user's mental model, not the data model.
6. **Safety & reversibility.** It's a money app — trust is the product. Destructive/irreversible
   actions (delete expense, leave group, mark paid) confirm or are undoable. The user is never
   afraid to tap because they can't predict the result.
7. **Tap budget.** Count taps for the job (above). Over budget → flag, and name the taps that
   could collapse.
8. **Recognition over recall.** The app remembers (last payer, participants, split mode,
   currency, preferred payment app). The user never re-enters what it already knows or holds a
   number in their head across screens.
9. **Feedback.** Every tap produces a visible result within ~100ms (state change, loading, toast,
   navigation). No tap feels like it did nothing. Async work (scan, sync, deep-link return) shows
   progress and a clear success/failure end-state.
10. **Progressive disclosure.** The heavy machinery (itemized claiming, conflicts, reconcile,
    placeholders, multi-currency) stays hidden until the user actually needs it. A simple even
    split never makes Sam walk past features he doesn't understand.

---

## How to run an audit

1. **Get the app on screen.** Build + launch on the simulator (invoke the `run` skill, or the
   iOS build from `CLAUDE.md`: `cd code && ./gradlew :shared:compileKotlinIosSimulatorArm64`,
   then run the iOS app). If you truly can't run it, fall back to the interactive design export
   (`design/Evenly-Standalone.html`) or reading the composables — but say so; a real run
   beats a read.
2. **Seed realistic state.** A cold audit needs a populated group: a few members (incl. a
   placeholder), a mix of expenses (even, itemized, a settled one), and a non-zero balance for
   the current user. An empty app hides every real friction.
3. **Pick a persona + job.** One at a time.
4. **Walk it cold.** Prefer the cold-subagent method. Screenshot every screen. At each step
   record: what you expected · where your eye went · what actually happened · the gap.
5. **Score against the rubric.** Note every failed heuristic with the screen and the specific
   moment.
6. **Rank and report** (format below).

---

## Output: the findings report

Lead with a **core-jobs scorecard**, then ranked findings. Severity:

- **P0 — blocks a core job, or risks the wrong money outcome, or the owe/owed direction is
  ambiguous.** These forfeit trust or the sale. Fix before beta.
- **P1 — job completes but the persona is confused / likely to ask for help or mis-tap.** The
  support-ticket tier.
- **P2 — friction, hesitation, avoidable taps, weak copy.** Polish that compounds.
- **P3 — nice-to-have, taste, delight.**

```
## Core-jobs scorecard
| Job | Persona | Result | Taps (budget) | Note |
|-----|---------|--------|---------------|------|
| Join from invite | Sam | ✅ / ⚠️ / ❌ | 4 (≤3) | ... |
...

## Findings (most severe first)
### [P0] <one-line defect> — <screen>
- **Persona & moment:** Sam, on the Balances tab, trying to answer "do I owe?"
- **Expected vs actual:** expected a clear "You owe Priya $12" → saw "−12.00" with no name
- **Why it's friction:** direction + counterparty aren't glanceable; this is the #1 competitor complaint
- **Suggested fix (small):** lead the row with "You owe Priya" as text; amount second
```

Keep fixes **small and surgical** — the point of auditing first is that every fix traces to an
observed problem, so the owner tweaks instead of rewriting flows. Don't propose a redesign when a
label change closes the finding.

---

## Build-time mode (the ingrained habit)

Before you call any UI change done:

1. Name the persona whose job this flow serves (usually **Sam** or **Diego** — the low-patience ones).
2. Walk *just that flow* cold on the simulator. Count the taps.
3. Clear every **P0/P1** you find. Log P2/P3 for later.
4. Grep your new user-facing strings against the [jargon blocklist](references/anti-patterns.md#jargon-blocklist).
5. Confirm: could Sam do this without you sitting next to him? If not, it's not done.

This is cheap (one flow, a few minutes) and it's the whole point — it stops friction from ever
reaching the beta testers, so their feedback is about taste, not "I couldn't figure out how to pay."
