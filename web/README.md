# `web/` — the claim surface

The browser half of `WEB_CLAIM_SPEC.md`: a guest scans the payer's QR, claims what they actually ate,
and leaves as a real person in the group. No app, no account, no password.

**Right now this holds only the money engine** (build-order step 4). The Svelte surface is step 5.

```
src/lib/money/
  allocate.ts        largest-remainder allocator            ← Allocator.kt
  billSplit.ts       the itemized engine                    ← BillSplit.kt
  itemizedShares.ts  extras on per-person subtotals         ← ItemizedAllocator.kt
  fromApi.ts         POST /web-claim/bill payload → engine  ← BillMaterializer.kt
test/vectors.test.ts the CI gate's TS half
```

```bash
npm ci             # one dev dependency: typescript, for the typecheck
npm test           # runs test-vectors/bill-split.json against this port
npm run typecheck
```

Needs Node ≥ 22.6. The tests run straight off the `.ts` sources via type stripping — no build step
and no test framework.

**Before changing anything here, read `AGENTS.md` in this directory.** The short version: Kotlin owns
the split math and this is a gated copy of it (`test-vectors/README.md`), and no Supabase client or
anon key may ever ship in this bundle.
