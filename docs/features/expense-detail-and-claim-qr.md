# Expense detail: working claim QR + carded comments/receipt (T4, T7)

**Status:** implemented on `fb/expense-detail`; server SQL, edge-function deploy and Vercel deploy are the owner's (see §7)
**Worktree / branch:** `fb/expense-detail` (from `feat/split-by-items-flow`)
**Mockup:** `design/expense-detail-cards-mockup.html` for T7 (and the QR entry point if it moves).

Paths below are under `code/shared/src/commonMain/kotlin/app/splitevenly/` unless noted. Read
`ui/AGENTS.md`, `web/AGENTS.md`, and the memory note on web-claim local-vs-prod before T4.

---

## T4 · The bill QR opens the item picker

**Owner report.** "The QR code we shared for expenses doesn't open the website for picking
expenses." The link shared was `split-evenly.app/j/01a0f581-…`.

**Diagnosis (planner, verified 2026-10-01 with curl).**
1. **That link is the group invite, not a bill claim.** `/j/<token>` is the group join link
   (`GroupExpensesTab.kt:98`, Group settings "Share group"). The bill claim URL is `/b/<token>`
   (`domain/expense/WebBillLink.kt:68–70`). So the tester shared the wrong QR — which means the UI
   made the wrong one the easy one to find.
2. **`/j/` has no web landing.** `web/vercel.json` rewrites only `/b/*` and `/admin`; there is no
   `/.well-known/apple-app-site-association` or `assetlinks.json` (both 404 live), so a `/j/` link
   opened by a camera never reaches the app and has nowhere useful to go.
3. **`/b/` is broken in production too.** `https://split-evenly.app/` → 200, but `/b/test` and
   `/j/x` → 404 (after the apex→www 308). The deployed project does not have this repo's
   `vercel.json` rewrites — either the claim app isn't deployed, or the Vercel project's root
   directory isn't `web/`. `USE_LOCAL_WEB_CLAIM_HOST` is already `false`, so the app side is fine.

**Acceptance criteria.**
- `https://split-evenly.app/b/<valid token>` serves the claim picker (`app.html`) in production.
  *(Deploy step is proposed to the owner, not executed — see coordination file.)*
- `https://split-evenly.app/j/<token>` serves a small landing: "You're invited to <group> on Evenly"
  + "Open in Evenly" (`splitevenly://j/<token>`) + store links. Reuse the marketing shell. No group
  data is shown without a server lookup that RLS permits; if that lookup isn't available, show the
  generic invite copy only.
- On the expense detail of an item-split bill, the **bill QR** is reachable from an obvious control
  labelled "Let friends pick their items" (not only via the "Who ordered what" card, L181), and the
  sheet title says it's for *this bill*, distinct from the group invite.
- The group invite sheet's copy says "Invite to group" so the two QRs can't be confused.
- Universal links / app links (AASA + assetlinks with the real team ID / SHA-256) are **out of
  scope for this commit** unless the IDs are already in the repo; if not, list the exact files and
  values needed in the completion report.

**Non-goals.** No change to the claim web app's picking logic or the `web-claim` edge function.

---

## T7 · Comments and receipt sit in their own cards

**Problem.** On expense detail, everything else is in an `EvCard` (L267, L354, L394), but the
comments block (~L570–600) and receipt thumbnails (`ReceiptThumb`, L1018) sit on the bare page, so
they read as unfinished.

**Acceptance criteria.**
- Comments (header row, collapse, bubbles, composer) are wrapped in one `EvCard` with the same
  padding/radius as the split card; the composer stays inside the card's bottom edge.
- Receipt thumbnails + upload state sit in their own `EvCard` titled "Receipt".
- Light and dark both checked; card spacing matches the rest of the screen.

---

## Test plan

| Criterion | Proof |
| --- | --- |
| `/b/` and `/j/` routing | `web/` local preview (`preview_start`) hitting both paths; `vercel.json` diff reviewed |
| QR entry point + copy | iOS sim on an item-split expense |
| T7 layout | iOS sim light + dark; `ux-firsttimer` |
| Both targets | AGENTS.md §5 compile pair + `testAndroidHostTest` |

## 7. Completion report

**Commits (branch `fb/expense-detail`).** `895b04c` + `6616b7d` mock (owner approved; `/j/` frame revised to
show the group name with a generic fallback) · `00d4b54` T7 · `f6e3f3a` T4.

**T7.** Receipt thumbnails (title "Receipt") and the whole comment thread, composer included, sit in
`EvCard(padded)` like the split card (`ExpenseDetailScreen.kt`). Checked on the iOS simulator in dark.
Light was not toggled on the simulator; the cards use the same theme tokens as the split card.

**T4 (app).** `BillPickCard` now has a full-width "Let friends pick their items" text button (no icon, no caption:
owner review 2026-10-01) under "Change picks"; `BillShareLinkScreen` top bar says the same; the group sheet is
titled "Invite to group" with "Anyone with this link joins <group>. To split just one bill, share that bill's
QR instead." Verified on the iOS simulator. Note `GroupExpensesTab.kt` InviteSheet (~L371) is also touched by
`fb/group-surface`; the hunk here is two lines.

**T4 (server + web).**
- `supabase/schema.sql`: `invite_preview_group_name(p_token text) returns text`, `security definer`, returns
  only `groups.name` for a non-deleted group, else NULL; revoked from public/anon/authenticated, granted to
  `service_role` only.
- `supabase/functions/web-claim/index.ts`: new action `invite-preview` (shape-checks the token, per-IP hashed
  rate limit via `public_write_log` kind `invite-preview` using `_shared/rateLimit.ts`, calls the RPC). Every
  outcome is HTTP 200 `{"groupName": string | null}`, so unknown, malformed, deleted, rate-limited and failed
  lookups are indistinguishable. `handler(sb, body, req)` now receives the request (needed for the IP).
- `web/`: `/j/:token` route (`router.ts`), `marketing/Join.svelte` (one plain `fetch` through `api.ts`, no
  Supabase client or key), `seo.ts` noindex row, `vercel.json` + `public/_redirects` rewrite to `/app.html`.
  Not prerendered. `npm test`, `npm run typecheck`, `npm run build` pass; the page was loaded on the Vite dev
  server and the fallback heading renders when the lookup fails. The success path against a real backend is
  untested until the owner steps below are done.
- Compiles: `:shared:compileAndroidMain` and `:shared:compileKotlinIosSimulatorArm64` green. `testAndroidHostTest`
  was not run (no logic under test changed). `ux-firsttimer` was applied as an inline cold read of the two UI
  changes (a labelled control replacing an ambiguous chip; cards with no new flow), no P0/P1 found, rather than
  a full skill walk.

**Owner steps (NOT performed).** In this order:
1. Apply SQL (dashboard SQL editor or MCP `apply_migration`, project `wfpfgbipjmkysalfmyub`), idempotent:
```sql
create or replace function public.invite_preview_group_name(p_token text)
returns text language sql stable security definer set search_path = public, pg_temp
as $$ select g.name from public.groups g where g.invite_token = p_token and g.deleted_at is null limit 1; $$;
revoke all on function public.invite_preview_group_name(text) from public, anon, authenticated;
grant execute on function public.invite_preview_group_name(text) to service_role;
```
2. Deploy the function: `supabase functions deploy web-claim --project-ref wfpfgbipjmkysalfmyub`
   (`verify_jwt = false` is already pinned in `config.toml`). Check:
   `curl -s -X POST https://wfpfgbipjmkysalfmyub.supabase.co/functions/v1/web-claim/invite-preview -H 'content-type: application/json' -d '{"token":"<real invite_token>"}'`
   expects `{"groupName":"..."}`; a bogus token expects `{"groupName":null}`.
3. Vercel: the production `/b/*` and `/j/*` 404s mean the project is not building `web/`. In the Vercel project
   settings set Root Directory `web`, Framework Preset Vite, Build Command `npm run build`, Output `dist`,
   Node >= 22.6, and set `VITE_WEB_CLAIM_URL` to `https://wfpfgbipjmkysalfmyub.supabase.co/functions/v1/web-claim`
   (without it `/j/` and `/b/` post to a relative path and always fall back). Then `cd web && vercel --prod`
   (or push). Verify `curl -sI https://split-evenly.app/b/<16+ char token>` and `/j/<invite token>` return 200,
   and open the `/j/` link to see the group name.
4. Do steps 1 and 2 before 3 so the page never ships ahead of its endpoint (it would only fall back, harmlessly).

**Out of scope / found.**
- Universal links and app links: need Apple Team ID + bundle `app.splitevenly` for
  `web/public/.well-known/apple-app-site-association` and the Android release SHA-256 for
  `web/public/.well-known/assetlinks.json` (package `app.splitevenly`), plus a `Content-Type: application/json`
  header rule for the AASA path. Neither value is in the repo. Until then `/j/` opened from a camera lands on the
  web page, whose "Open in Evenly" uses `splitevenly://j/<token>`.
- The `/j/` rate limit is 30 requests/hour/IP (the shared `_shared/rateLimit.ts` constant); a shared NAT could
  trip it, which only costs the name (fallback copy).
- `design/system/scripts/check-design-system.mjs` fails on a missing `design/system/dist/catalog.js` in this
  worktree (untracked build output), unrelated to this change; no catalog entry covers these screens.
- Worktree setup: `run-ios-sim.sh` needed the gitignored `GoogleService-Info.plist` copied from the main
  checkout into `code/iosApp/iosApp/` (left untracked and ignored).
