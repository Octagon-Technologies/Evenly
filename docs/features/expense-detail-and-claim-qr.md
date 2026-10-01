# Expense detail: working claim QR + carded comments/receipt (T4, T7)

**Status:** mock done, awaiting owner sign-off (no Compose / web code written yet)
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

**State (2026-10-01): STOPPED at the mock gate (AGENTS.md §7). T4 and T7 are not implemented.**
T4 was not split out as "no UI": it adds a new web page (`/j/` landing), moves/relabels the bill-QR
control and retitles the invite sheet, all visual. One mock covers both tasks:
`design/expense-detail-cards-mockup.html` (T7 light+dark vs before; T4 entry point before/after; both
sheet titles; the `/j/` landing). No compile was run because no source changed, and no feature commit was made.

**Owner decisions needed.** (1) Approve Comments + Receipt as `EvCard`s (section title "Receipt").
(2) Approve a full-width outlined "Let friends pick their items" button in `BillPickCard`
(replacing the small "Share" chip) with the line "Opens a QR for this bill only. No app or account
needed."; bill QR sheet titled "Let friends pick their items" (today `BillShareLinkScreen` says "Let everyone
pick"); group sheet retitled "Invite to group" (today `Invite to $groupName`, `GroupExpensesTab.kt:371`).
(3) Approve generic-copy-only `/j/` landing.

**Implementation plan once approved.** T7: wrap the comments `Column` (ExpenseDetailScreen.kt ~L570) and the
receipts block (~L333) in `EvCard`; commit `feat(expense-detail): comments and receipt in cards (T7)`.
T4 (app): edit `BillPickCard.kt` and the two titles. T4 (web): in `web/` add `{kind:'join'}` to `lib/router.ts`
(`/j/<token>`), a `marketing/Join.svelte`, an entry in `prerender-entry.ts` `PRERENDERED`, `seo.ts` row (noindex),
and `/j/(.*)` -> `/index.html`-style rewrite in `vercel.json` + `public/_redirects` (the three-file rule in
`web/AGENTS.md`); run `cd web && npm ci && npm test && npm run typecheck`, preview both `/b/` and `/j/`.
Commit `feat(web-claim): bill QR entry point and /j invite landing (T4)`.

**Proposed, NOT performed (outward-facing).**
- Production `/b/*` and `/j/*` 404 on split-evenly.app: the Vercel project is not serving this repo's
  `web/` rewrites. In Vercel project settings set Root Directory = `web`, Framework Preset = Vite,
  Build Command `npm run build`, Output Directory `dist`, Node >= 22.6; then from the repo root run
  `cd web && vercel --prod` (or push and let the Git integration deploy) and verify with
  `curl -sI https://split-evenly.app/b/<16+ char token>` (expect 200 with app.html) and `/j/x`.
- Per memory note: before publishing, confirm `USE_LOCAL_WEB_CLAIM_HOST` is false (it is) and no `web-claim`
  edge-function drift. No edge-function change is needed for T4.
- Universal/app links: need Apple Team ID + bundle `app.splitevenly` for
  `web/public/.well-known/apple-app-site-association`, and the Android release-key SHA-256 for
  `web/public/.well-known/assetlinks.json` (package `app.splitevenly`). Neither value is in the repo; also add
  `Content-Type: application/json` headers for the AASA path in `vercel.json`.
