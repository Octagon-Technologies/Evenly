# Anti-patterns & the jargon blocklist

Two catalogs: (1) failure modes drawn from real competitor reviews and ShareCost's own design —
the specific traps to hunt for; (2) the jargon blocklist for heuristic 5.

---

## Competitor failure modes (from real 1–3★ reviews in `app_store_complaints/`)

ShareCost exists *because* these are the things people hate. If our app reproduces any of them,
we've lost our reason to exist. Hunt for each:

1. **Attribution confusion — "I can't tell who owes whom."** (Tricount 3★: *"it shows the rest of
   people owes money to me and not the person who paid… this app miss the basics."*) → The
   owe/owed direction and counterparty must be glanceable and correct everywhere. This is the
   heartbeat (Job 4). Treat any ambiguity as P0.
2. **Forced full-balance settlement.** Competitors make you settle the whole balance. ShareCost's
   headline feature is paying a *single* expense. If the settle flow nudges toward "pay everything"
   and hides the per-expense path, we've thrown away the differentiator.
3. **Money custody / caps / KYC.** (Splitwise 1★: transfer limits, *"you'd be better off working
   with Claude to build your own tracker + Zelle/Venmo/PayPal."*) → Our deep-link-to-your-own-app
   handoff must be obvious and frictionless; it's literally the thing this reviewer wishes existed.
4. **Flat, undifferentiated expense list.** Repeatedly called "the single most confusing thing"
   about the incumbent. → Expense views should be scannable (grouped, clear payer/amount/status),
   never an undifferentiated wall.
5. **No per-person transaction view.** Reviewers want to tap a person and see everything between
   the two of you. → Verify Balances → per-person drill-down exists and is obvious.
6. **Paywalls / gated basics.** Our whole pitch is "the free tier is the full product." Nothing
   core should ever read as locked or upsell-shaped.
7. **Silent changes killing trust.** (Retroactive-add complaints: *"I had to rebuild the whole
   trip."*) → When the app changes a split (member added, conflict resolved), the user must see
   *what changed and why*, legibly.

---

## ShareCost-specific traps (complexity leaking from the architecture)

The app has genuinely sophisticated machinery. The risk is never the machinery — it's the
machinery *showing through* to a user who shouldn't have to know it exists.

- **The itemized bill flow is a mode within Add Expense.** Guard the simple even-split path: a
  user doing the 95% case must never have to understand items, claims, or "By what each had."
- **"Done" reads as "paid."** On `BillClaimScreen`, claiming your items is not paying for them.
  If "Done" implies settlement, that's a real money-confusion bug.
- **Auto-charging via the shared set.** Adding a friend to a shared item charges them by default.
  Correct behavior — but it must be *visible* and one-tap removable, never a silent surprise.
- **The Conflicts tab as a permanent fixture.** A "Conflicts" tab sitting there when nothing is
  wrong is alarming and confusing. Prefer surfacing it only when there's something to resolve.
- **"Reconcile" / "Placeholder" as concepts.** Both are defensible labels, but the *flow* must
  lead with the plain-language question ("Are any of these you?" / "Priya added you — tap your
  name"), never the technical noun.
- **Two settle entry points** (per-person vs per-expense). Make sure a user lands on the one that
  matches their intent instead of getting lost between them.
- **The strikethrough amount signature** (remaining over crossed-out original). Elegant, but a
  cold user may not know why a number is crossed out. Verify it reads as "you've paid some of
  this," not "error."
- **Multi-currency / FX.** Keep it invisible until a foreign-currency expense actually appears.

---

## Jargon blocklist

Heuristic 5: none of these internal words may appear in **user-facing** text (labels, titles,
body, empty states, toasts, errors). They're fine in code, comments, and route names. The current
UI is mostly clean — this keeps it that way as new screens are built.

**Never show the user:**

| Internal word | Why it's wrong | Say instead |
|---|---|---|
| materialize / derive | data-model verb | (say nothing — just show the result) |
| conflict | alarming; means "collision" internally | "Two people edited this" / "Needs a quick review" |
| parked | internal state | (don't surface; frame as "waiting for you to pick") |
| reconcile | technical | "Claim a past member as me" / "Are any of these you?" |
| placeholder | jargon | "someone Priya added" / the person's name |
| allocation / apply | ledger verb | "payment" / "pay back" |
| row version / base version | sync internals | (never user-facing) |
| CAS / compare-and-swap | pure implementation | (never user-facing) |
| tombstone / soft-delete | data-model | "deleted" / "removed" |
| sync state / dirty / synced_version | plumbing | (never user-facing) |
| split mode / itemize | mild jargon | "Even split" / "By what each had" (already used — good) |
| settle | borderline; OK as a section, weak as a button | button: "Pay Sam back" / "Record a payment" |

**Preferred user-facing verbs (what users actually think):** *pay back · record a payment · who
owes who · split · I had this · add someone · this is me · done.*

**Quick check for a new screen:**
```
grep -rEi 'materiali|reconcil|placeholder|allocat|tombstone|\bCAS\b|row.?version|base.?version|sync.?state|\bdirty\b|\bparked\b' \
  code/shared/src/commonMain/kotlin/da/chelimo/sharecost/ui/
```
Hits inside `//` comments or route/enum names are fine; hits inside a user-visible string literal
are the finding.
