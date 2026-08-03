# `web/` — the claim surface

Governs `web/**`. The browser half of `WEB_CLAIM_SPEC.md`: a guest scans the payer's QR, claims what
they actually ate, and leaves as a real person in the group. No app, no account, no password.

This is **not** a web version of Evenly. One bill, one job, 72 hours. Spec §9 lists what that
deliberately excludes — no group view, no balances, no settle-up, no expense creation, no offline.
If you are adding a second screen that is not in `design/web-claim-mockup.html`, stop and re-read it.

Currently this tree holds only the money engine (spec build-order step 4). The Svelte surface is
step 5; extend this file when it lands.

## The money port is a copy, and the copy is gated

`src/lib/money/` is a hand-port of the Kotlin split math — `allocate.ts` ← `Allocator.kt`,
`billSplit.ts` ← `BillSplit.kt`, `itemizedShares.ts` ← `ItemizedAllocator.kt`, `fromApi.ts` ←
`BillMaterializer.kt`'s row adapters.

**Kotlin is the authority.** This port is authoritative for what the guest *sees* and never for what
the ledger *records*. If they disagree, Kotlin wins and the web is stale by one poll.

- **Never change a split rule on one side only.** Both halves land in the same commit, with a vector
  that would have caught the old behaviour. `test-vectors/README.md` is the procedure.
- **Structure mirrors the Kotlin file, branch for branch.** Resist tidying it into something more
  idiomatic: being diffable against `BillSplit.kt` *is* the safety mechanism.
- **Money is integer subunits.** No floats, no `toFixed` arithmetic. `allocate` does its
  multiply-then-divide in `BigInt` because Kotlin does it in `Long`, and a double silently rounds
  past 2^53. No currency conversion either — show the bill's own currency (spec §12.3).
- The gate is `.github/workflows/money-vectors.yml`, and `code/shared/build.gradle.kts` declares the
  vectors file as a test input so a vectors-only edit cannot pass as UP-TO-DATE.

## The security boundary is the whole feature

**No Supabase client and no anon key ship in this bundle** (spec §4.1). Every guest read and write
goes through the `web-claim` edge function, which holds the service key and does its own
authorisation per endpoint. A browser talking to PostgREST directly turns a known internal risk into
a public breach — RLS is still `for all to authenticated using (true)`.

The bill token is bill-scoped and expires; the session token is group-scoped and durable. They are
two different things with two different lifetimes (spec §2.2) and conflating them is the mistake that
section exists to prevent.

## Behaviour rules that look like preferences and are not

- **A claimed line is never hidden** (§2.5). Every line is always visible whatever its state; a
  claimed line changes what it *offers* you, never whether it exists. Filtering to "what's left"
  makes a shared plate unjoinable and silently wrong.
- **The claim affordance is a chip in the people row** (§2.6), never a button in the price column,
  and there is no prose in the claim list — chips only.
- **Editing a line needs the payer's approval; joining a claim does not** (§2.7). These look
  inconsistent and are not. Do not harmonise them.
- **Exact duplicate names are blocked, not warned** (§2.4), with one-tap suffixes and an always-visible
  "Wait, I am that Purity" escape hatch.
- **No optimistic writes** (§6). A failed write shows a retry, never a fake success — a guest who
  walks away believing they claimed is worse than a spinner.
- **The install prompt is a one-line footnote** (§2.8). Never a card, never a button, nothing to
  dismiss.

## Toolchain

Node ≥ 22.6. Tests run straight off the `.ts` sources via type stripping — no build step and no test
framework, because a money gate that needs a toolchain is a money gate that gets skipped.

```bash
cd web && npm ci && npm test && npm run typecheck
```
