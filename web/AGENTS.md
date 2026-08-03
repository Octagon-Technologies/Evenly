# `web/` — the claim surface

Governs `web/**`. The browser half of `WEB_CLAIM_SPEC.md`: a guest scans the payer's QR, claims what
they actually ate, and leaves as a real person in the group. No app, no account, no password.

This is **not** a web version of Evenly. One bill, one job, 72 hours. Spec §9 lists what that
deliberately excludes — no group view, no balances, no settle-up, no expense creation, no offline.
If you are adding a second screen that is not in `design/web-claim-mockup.html`, stop and re-read it.

`src/lib/money/` is the ported split math (step 4); `src/screens/` is the Svelte surface, frames 1-9
(step 5). `src/lib/mock.ts` is the walkthrough fixture behind `?mock` and is dead code in a build.

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
  past 2^53.
- **The bill's own currency, always, never converted** (spec §12.3, answered). The app has FX
  (`refresh_fx_rates`); this bundle has none, ships no Supabase client, and has **zero runtime
  dependencies** — there is nothing to convert with and nothing to convert from. A guest converting in
  her head is a guest paying the wrong number. Do not "fix" this later by adding a rate.
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
- **An item edit is announced; joining a claim is not** (§2.7). Both apply instantly. An edit moves
  the bill total and therefore everyone's money, so it lands in the change log under the item card and
  the payer is told in the app; joining moves two people's with both at the table, so nothing is said.
  These look inconsistent and are not. Do not harmonise them, and **do not re-add an approval gate** —
  `store.editLine` applies, `store.undoChange` takes it back, and **anyone on the bill may undo**.
- **The change log is a section below the lines, never prose inside them.** "No prose in the claim
  list" (§2.6) is about the rows themselves: chips only. The log renders on the minority of bills where
  anyone edited anything, and an undone entry stays legible rather than vanishing.
- **An `ADD` also claims the new line for whoever added it.** The sheet says "Add it and claim it", and
  someone typing in the dessert they ate has already told us they ate it.
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
