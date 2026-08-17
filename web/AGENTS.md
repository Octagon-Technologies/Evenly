# `web/` — the claim surface

Governs `web/**`. Two surfaces share this one Vite/Svelte bundle: the claim flow (the browser half
of `WEB_CLAIM_SPEC.md` — a guest scans the payer's QR, claims what they actually ate, and leaves as a
real person in the group, no app, no account, no password) and, as of 2026-08-04, the marketing site
(`src/marketing/` — home, privacy, terms) that lives at the same domain. `src/lib/router.ts`'s
`resolveRoute` is what decides which one a given URL gets.

**The claim flow is still not a web version of Evenly.** One bill, one job, 72 hours. Spec §9 lists
what that deliberately excludes — no group view, no balances, no settle-up, no expense creation, no
offline. If you are adding a second *claim* screen that is not in `design/web-claim-mockup.html`,
stop and re-read it. That constraint does not extend to `src/marketing/`, which has its own approved
mockup (the "Finy-inspired" home/privacy/terms artifact) — a new marketing page is in scope there.

`src/lib/money/` is the ported split math; `src/screens/` is mostly the claim flow's Svelte surface,
frames 1-9, plus `ThankYouScreen.svelte` (feedback build-order step 5, `ADMIN_FEEDBACK_SPEC.md` §10) —
standalone and unrouted until step 6 wires up `/feedback` itself. `src/marketing/` is the home/privacy/terms pages, styled by `src/marketing.css` (`site-`-prefixed
selectors, tokens scoped under `.site-root` — never `:root` — so they can never collide with
`app.css`'s claim-flow tokens in the same bundle). `src/lib/mock.ts` is the claim flow's walkthrough
fixture behind `?mock` and is dead code in a build.

**The two surfaces do not share `#app`'s box model.** `app.css`'s old `#app` rule (fixed height,
480px mobile column) is now `.claim-shell`, applied only around the claim-flow branch in
`App.svelte`. Putting it back on `#app` itself reintroduces a 480px cap on the marketing pages —
found once already; don't reintroduce it.

## The money port is a copy, and the copy is gated

`src/lib/money/` is a hand-port of the Kotlin split math — `allocate.ts` ← `Allocator.kt`,
`billSplit.ts` ← `BillSplit.kt`, `fromApi.ts` ← `BillMaterializer.kt`'s row adapters. (`itemizedShares`
was a *third* engine, ported and vector-pinned but called by nothing on either side; it was deleted with
its vectors rather than left reading as live money code.)

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
