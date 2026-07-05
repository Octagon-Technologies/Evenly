# Cold-walkthrough scripts — the six core jobs

Each script names the **real routes/screens** so you know you're testing the actual flow. Walk
each as the named persona, cold (no architecture knowledge). Record at every step: *what I
expected · where my eye went first · what happened · the gap.* Trap spots are places this flow is
likely to lose a first-timer — verify them specifically, don't assume they're fine.

---

## Job 1 — Join a group from an invite (Sam, ≤ 3 taps)

**Path:** deep link `sharecost://j/{token}` (or paste a link → `JoinByLinkSheet`) → `JoinRoute`
→ `JoinGroupSheet` → `GroupHome` (Expenses tab).

**Cold script:** You got a text with a link. You don't have the app / just installed it. Tap the
link. What do you see? Do you understand what group this is and who's in it? The sheet may ask
"Are you one of these?" (claim a placeholder identity). **Does Sam understand that question?** He
may not know the organizer pre-entered his name. Then he lands in the group.

**Trap spots**
- The placeholder-claim picker on `JoinGroupSheet`: Sam has no idea what a "placeholder" is or why
  his name is already there. Does the copy explain it in his terms ("Priya already added you — tap
  your name")?
- First landing on `GroupHome`: does Sam immediately see *his* state (what he owes), or a generic
  expense list where he has to hunt?
- Account friction: does joining force an account/onboarding before he can see anything? Every
  screen before "in the group" spends Sam's very low patience.

---

## Job 2 — Add an even-split expense (Priya, ≤ 6 taps)

**Path:** `GroupHome` → FAB/+ → `Route.AddExpense` → `AddExpenseRoute` → `AddExpenseScreen` →
save → back to `GroupHome`.

**Cold script:** Priya paid $60 for dinner, splitting evenly with 3 people. Tap +. Fill title,
amount, payer, participants; leave split on Even. Save. Is every field's default already right so
she barely touches them (sticky payer = her, participants = last set, currency = group)?

**Trap spots**
- The front door: `AddExpenseScreen` merges Even/Percent/Exact and an itemized "By what each had"
  mode. Does Priya, who wants the *simple* case, ever have to understand itemizing to do an even
  split? (Progressive disclosure — heuristic 10.)
- Payer field: the spec allows an outside-the-group payer. Does that option confuse the 95% case?
- Participants: is "everyone" the obvious default, with select-all/deselect discoverable?
- Save affordance: is the primary action unmistakable and labeled plainly?

---

## Job 3 — Split an itemized bill: scan → claim → finish (Diego, ≤ 8 taps)

**Path:** `AddExpenseScreen` → "By what each had" → "Scan receipt" (`BillEditRoute.runScan`, OCR)
→ `BillEditScreen` (verify items/extras, pick participants) → save → `Route.ClaimBill` →
`BillClaimScreen` (tap what I had, live total) → Done.

**Cold script:** Diego is at the table. Someone scanned the receipt. He needs to tap the 2 tacos
he ate and see his number. This is the **lowest-patience, highest-complexity** flow — the one most
likely to feel like "work."

**Trap spots**
- Scan → verify handoff: OCR pre-fills items; does the user understand they must *verify* before
  money is computed, without it feeling like data entry?
- `BillClaimScreen` mental model: does Diego understand he taps *quantities* of items he had? Is
  "shared with" / leftover-splitting legible, or does it silently charge him for things?
- The auto-union shared set: adding a friend charges them by default. Is that visible and one-tap
  removable, or a surprise?
- Live total: does Diego's running number ("your tab") update visibly on each tap (feedback,
  heuristic 9)?
- Over-claim / unclaimed reconciliation (`ItemStatus`): is the one surfaced status clear, and does
  it block "Done" confusingly?
- "Done" vs "paid": does Diego think tapping Done means he's paid? (Claiming ≠ paying — a known
  ambiguity.)

---

## Job 4 — "Do I owe or am I owed, and how much?" (Sam, ≤ 1 tap) — THE HEARTBEAT

**Path:** `GroupHome` → Balances tab → `GroupBalancesTab` (two sections: "You owe" / "Owes you",
rows expandable to per-expense breakdown).

**Cold script:** Sam opens the group. Without tapping anything, can he answer: do I owe or am I
owed, to whom, how much? Then expand one row — does the per-expense detail make sense?

**Trap spots**
- Direction clarity: is "You owe" vs "Owes you" instantly unmistakable **without relying on
  color** (design bans green; some users are colorblind)? This is the exact Tricount 3★ failure.
- The signature amount style (mono "remaining" over muted strikethrough "original"): does a
  first-timer read it correctly, or is the strikethrough confusing ("why is there a crossed-out
  number?")?
- Landing tab: does the group open somewhere that answers Sam's question, or does he have to *find*
  the Balances tab first (costing his ≤1-tap budget)?
- Per-person view: can Sam tap a person and see everything between him and them? (Competitor
  reviewers explicitly ask for this.)

---

## Job 5 — Settle up & record a payment (Mara, ≤ 5 taps)

**Path:** `GroupBalancesTab` → expand a "You owe" row → "Settle up" → `Route.SettlePerson` →
`SettlePersonScreen` (tick expenses, pick payment app) → "Pay with Venmo" (deep link) →
`DeepLinkConfirmSheet` → confirm. *(Single-expense variant: `ExpenseDetail` → Settle →
`SettleSingleSheet`.)*

**Cold script:** Mara owes Sam $24. She wants to pay through Venmo and mark it done. This is
ShareCost's **signature advantage** (pay a single expense, through your own app, no bank link) —
it has to be the smoothest thing in the app or the whole thesis is forfeit.

**Trap spots**
- Two entry points (per-person `SettlePersonScreen` vs per-expense `SettleSingleSheet`): does Mara
  find the one she wants, or get lost between them?
- The tick-which-expenses list: is it obvious everything's pre-ticked and she can pay a subset?
- Payment-app choice: is Sam's *preferred* app clearly the default? What if Mara doesn't have it?
- The deep-link round trip: after Venmo opens pre-filled and she comes back, is the "Did it go
  through?" confirmation obviously the last step — and forgiving if she bailed?
- "Mark as paid" vs "Pay with Venmo": are both paths clear (she may have paid cash)?
- Does the balance visibly drop afterward (feedback + closure — Mara wants the number gone)?

---

## Job 6 — Claim a past identity / resolve a conflict (Priya, ≤ 4 taps)

**Path A (reconcile):** `GroupSettings` → "Reconcile" / "Claim a past member as me" →
`Route.Reconcile` → `ReconcileScreen` ("Are any of these you?") → multi-select → "These are me".

**Path B (edit collision):** `GroupHome` → Conflicts tab → `GroupConflictsTab` → pick a side
("Keep Bob's" / "Use yours"). **Path C (join-late):** Conflicts tab → `Route.IncludeMember` →
`IncludeMemberSheet` → include the member.

**Cold script:** These are the app's most conceptually advanced surfaces. Walk each and ask: does
a normal person understand *what happened* and *what their choice does*?

**Trap spots**
- "Reconcile" as a screen title: the word is technical. The subhead ("Are any of these you?") is
  good — does the flow lead with the plain question, not the jargon?
- Conflicts tab existence: does a first-timer even understand why a "Conflicts" tab exists, or is
  it alarming? Should it only appear when there's something to resolve (progressive disclosure)?
- Edit-collision diff: does Priya understand she's choosing between two versions of one expense,
  led by *her own* share, not a bare total?
- Irreversibility: after picking a side / merging identities, is it clear what changed and can it
  be undone?
