# `domain/` — money math and pure logic

Governs `domain/**`. This layer is **pure**: no Room, no Supabase, no Compose, no I/O. That is what makes it
unit-testable on both JVM and Native, and it is the reason `MoneyLoop` / `ConflictFlow` tests can run in
`iosSimulatorArm64Test`. Persistence rules live in `../data/AGENTS.md`; screens in `../ui/AGENTS.md`.

**Keep it pure.** If a change here needs a repository, a DAO, or a clock, take it as a parameter instead. The
repositories already pass collaborators in (see the optional-ctor-dep pattern in `../data/AGENTS.md`).

## Money is integer subunits, never floating point

All amounts are `*_subunits` integers in the expense's own currency. There is no shared display currency and
balances are **per-currency** — never sum across currencies to produce one number.

**Every split runs through the largest-remainder allocator (`allocate`).** It is penny-exact by construction:
the remainders are handed out deterministically so the parts always sum back to the total. A $10 line over 3
units bills 334/333/333 with no leak. If you are writing a division that produces money, you are almost
certainly meant to call `allocate` instead.

## `BillSplit.kt` — the itemized engine

`splitBill` is the pure function that turns line items + claims into per-person amounts. It is the single
source of truth for itemized money; `BillRepositoryImpl.materializeShares` only *persists* what this returns.

**A line's units are distributed by portions.** A portion is a *quantity of units + the people splitting it*:

- a **solo** assignment is an `item_claims` row (per-user quantity);
- a **shared slice** is a set of `item_shares` rows sharing a `portion_id` + `quantity`.

MULTIPLE distinct slices coexist on one line — "Andrew ×2, Bob ×3, {Bob,Mary} ×1, 2 left" — which the old
single all-leftover set could not express. A person is in at most one shared slice per line (enforced by the
active `(item_id,user_id)` unique index). A **null `portion_id` is a legacy all-leftover slice** and must
keep reading byte-identically, so old bills are unchanged.

The portions path is penny-exact and returns `perItemByUser` (the per-line "who pays what"). Leftover units
reconcile as UNCLAIMED. Over-claim is **surfaced, not capped** — `ItemStatus` is RESOLVED / UNCLAIMED /
OVERCLAIMED and the UI shows the gap rather than silently rounding it away.

**The line total is the entered source of truth.** `expense_items.line_total_subunits` is what receipts print
("4 … $96.00") and stays penny-exact even when it doesn't divide evenly. Per-unit is **derived** for display
(`perUnitSubunits` = round(total ÷ qty)). `unit_price_subunits` is **vestigial** — kept populated with the
rounded per-unit for backward compat, but read `line_total_subunits` as truth. `splitBill` splits each line
total across its claimed units via the allocator, so claiming 2 of 4 units pays 2 of the 4 slices.

**Bill extras:** tax, gratuity, and **other charges** split **proportionally**, tip **always splits
evenly**, discount is **negative-proportional** — all penny-exact through `allocate`.
`otherChargesSubunits` (added 2026-08-08) is a printed charge that is none of the others: a delivery fee,
bottle deposit, bag fee, or card surcharge. It shares tax's proportional bucket, and `TabBreakdown.taxSubunits`
folds all three — the breakdown explains one person's number, and splitting it three ways when they ride
identically tells the reader nothing they can act on. The bill *editor* keeps them apart, because there the
amounts are being entered. The `ItemizedAllocator` still supports
`TipSplitMode.PROPORTIONAL`, but the editor no longer exposes the toggle and always sends `EVEN`; leave the
domain support in place, just don't wire a new UI to it.

## Settlement: `remaining` is derived, never stored

A share's `remaining` is `owed − Σ(applied allocations of non-voided settlements)`, and the payer's own share
is always 0. An expense is settled iff every share's derived remaining is 0. Allocation runs **oldest-first**
within whatever set the payment is scoped to. There is no stored "settled" flag to go stale — do not add one.
The persistence consequences are in `../data/AGENTS.md`.

## This math is implemented twice. Changing it here is half the job.

`Allocator.kt`, `BillSplit.kt` and `ItemizedAllocator.kt` are hand-ported to TypeScript in
`web/src/lib/money/` so a web guest's total is instant on a restaurant connection
(`WEB_CLAIM_SPEC.md` §2.10). **Kotlin is the authority** — it is what the ledger records; the port is
only what the guest sees, and is allowed to be stale by one poll, never to disagree.

`test-vectors/bill-split.json` runs against both engines and a divergence is a red build
(`.github/workflows/money-vectors.yml`). So a rule change here is not done until:

1. the TS port changes with it, in the **same commit**;
2. a vector that would have caught the old behaviour is added and recorded from Kotlin —
   `cd code && EVENLY_VECTORS_RECORD=true ./gradlew :shared:testAndroidHostTest --tests '*BillSplitVectorsTest*'`.

Never record to turn a red build green; see `test-vectors/README.md`. The vectors file is declared as
a test input in `shared/build.gradle.kts` precisely so a vectors-only edit cannot pass as UP-TO-DATE.

## Categories

`CategoryDefaults` holds the built-in set in app code and mirrors the legacy `ExpenseCategory` ids and
colors. A group's effective categories are defaults until it customizes; see `../data/AGENTS.md` for the
copy-on-write materialization.

## Tests are the specification for this layer

`commonTest` and `iosTest` cover the money loop and the conflict flow. When you change an allocation rule,
add the case that would have caught the old behavior — this is the layer where a regression is a wrong dollar
amount in someone's real dinner, so a test here is worth more than a paragraph anywhere else.

```bash
cd code && ./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test
```
