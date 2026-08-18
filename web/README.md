# `web/` — the claim surface, and the marketing site

One Vite/Svelte bundle, two surfaces. The claim flow is the browser half of `WEB_CLAIM_SPEC.md`: a
guest scans the payer's QR, claims what they actually ate, and leaves as a real person in the group.
No app, no account, no password. Alongside it, at the same domain, `src/marketing/` is the waitlist
(which is what `/` serves until launch), the parked home page at `/home`, and the legal pages.
`src/lib/router.ts`'s `resolveRoute` picks between them from the URL — a bill token (`/b/:token` or
`?b=`) always wins; everything else is the marketing site.

`npm run build` runs `vite build` and then `prerender.mjs`, which server-renders each marketing route
into its own HTML file so the site is readable without executing the bundle. `src/lib/seo.ts` holds
the per-route title, description, canonical and `robots`, and is the file to edit for any of them.

```
src/lib/money/
  allocate.ts        largest-remainder allocator            ← Allocator.kt
  billSplit.ts       the itemized engine                    ← BillSplit.kt
  fromApi.ts         POST /web-claim/bill payload → engine  ← BillMaterializer.kt
src/screens/          the claim flow's Svelte screens, frames 1-9
src/marketing/         home, privacy, terms — styled by src/marketing.css, not app.css
test/vectors.test.ts  the CI gate's TS half
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
