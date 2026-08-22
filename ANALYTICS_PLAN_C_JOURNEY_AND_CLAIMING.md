# Plan C — Onboarding funnel, placeholder-claim sourcing, scan cost visibility, web claim tracking

**Session type:** KMP client + web + (optional) Supabase read. Builds on top of `ANALYTICS_PLAN_A_SERVER_LEDGER.md`
and `ANALYTICS_PLAN_B_CLIENT_FUNNEL.md`, both of which are **already fully implemented** — treat their
"Steps" sections as history, not TODOs.

## 0. Source of this plan

Distilled from a PostHog in-app AI chat conversation (2026-08-22) plus this repo's actual code, verified
directly rather than taken on the widget's word. The widget's own picture of the codebase is partial —
two of its claims turned out to be stale (§1). This document only proposes work for gaps confirmed against
real files.

**Disclaimer carried over from the user:** PostHog's "restaurant vs. general expense tracker" is the same
distinction as `split_approach_chosen` / `expense_added.split_mode` (evenly vs. by-item). No separate
tracker is needed — that ask is already satisfied.

## 1. Corrections to PostHog's analysis (do not re-add these)

| PostHog claimed | Reality | Where |
|---|---|---|
| `group_joined` is missing | Already implemented | `AnalyticsEvents.GROUP_JOINED`, fired in `GroupRepositoryImpl.joinByToken` |
| `expense_added` needs an `expense_type` (restaurant/general) property | Already covered by the existing `split_mode` property (per the disclaimer above) | `.posthog-events.json` entry for `expense_added` |
| Scan model / fallback / token / cost tracking is entirely missing | Already fully built **server-side** | `supabase/functions/extract-receipt/index.ts` — see §4 |
| — | `ANALYTICS_PLAN_A_SERVER_LEDGER.md`'s checklist is already executed (widened `receipt_scan_log`, `scanId`, cost columns) | Treat as done |

What's left, confirmed genuinely missing by reading the actual code:

- Onboarding has **zero** analytics calls anywhere (confirmed by grep — `capture(` only appears in
  `BillRoutes.kt`, `LedgerRoutes.kt`, `ProRoutes.kt`).
- No `placeholder_claimed` event exists anywhere (only `PLACEHOLDER_ADDED` / `PLACEHOLDER_NOT_ME`).
- `expense_added` has no `entry_method` (manual vs. scan) property.
- The scan cost/latency/model data that already exists lives only in `receipt_scan_log` (Supabase) — it
  is invisible inside PostHog today. This is a genuine gap, but the fix is a **choice**, not obviously
  "add more capture() calls" (see §4).
- `web/` has **no PostHog SDK at all**, and is deliberately built with zero runtime dependencies
  (`web/AGENTS.md`). Adding tracking there is an architectural decision, not a one-line addition.

## 2. Onboarding step funnel

### What the flow actually is (not PostHog's guess)

Two screens, five real steps, not the "intro slides → name → sign-in → details" shape PostHog assumed:

1. `WelcomeScreen.kt` — a 5-slide product-pitch carousel, wired via `WelcomeRoute`.
2. `OnboardingScreen.kt`, whose own internal `steps` list is: `name` → `currency` → `handle` (optional
   payment handle) → `analytics` (this is the user's own *tracking consent toggle*, not an
   instrumentation point — don't confuse the two) → `notify` (the one step that reaches the OS
   notification-permission prompt, per `platform/AGENTS.md`'s "a permission is earned, never sprung" rule).

**Open question for you:** neither screen contains a sign-in step or an identity-claim step. If your
mental model of onboarding includes "they sign in" and "they claim their identity" as steps, either that
UI doesn't exist yet, or it lives somewhere this pass didn't associate with onboarding (e.g. sign-in
gates entry to `WelcomeRoute`/`OnboardingRoute` rather than being a step inside them). Worth confirming
before Claude wires events to the wrong screen.

### Proposed events

- `onboarding_step_viewed` / `onboarding_step_completed`, both with a `step` property using the real
  step ids above (`welcome_slides`, `name`, `currency`, `handle`, `analytics_consent`, `notify`) rather
  than PostHog's invented ones — a funnel is only useful if the property values match what's on screen.
- `onboarding_completed`, fired once, when `OnboardingRoute` finishes and the user lands on the main app.

### Fire-once mechanism

Don't invent one — the pattern already exists. `WiredScreens.kt` uses a `SecureStorage`
`"welcome_seen"` boolean key stamped on skip/continue (same idiom reused in `WebClaimRoutes.kt` for
`changesSeenKey`). `onboarding_completed` should use the identical pattern with its own key, not a new
mechanism.

## 3. Placeholder-claim sourcing

Your 3 assumed avenues don't quite match what's in code — there are 4 real claim surfaces, per
`IDENTITY_CLAIM_SPEC.md` and the repository layer:

1. **Claim during group join** — `GroupRepositoryImpl.joinByToken`, when `claimPlaceholderId` is passed
   from `JoinGroupSheet`. This is your avenue #2 ("card when they join").
2. **`IdentityClaimCard`** — the always-expanded in-group "is this you?" card (spec §2.1, §3.1),
   surfaced inside `GroupExpensesTab`. Fires while browsing a group, not strictly "at join."
3. **The full-screen `ReconcileScreen`** (spec §3.2) — reached from the card's "see all" or similar.
4. **Group settings → Members row** (spec §2.2, §3.5) — your avenue #3.

No dedicated "claim at end of onboarding" surface was found — confirm with the questions in §2 before
assuming it exists.

### Proposed event

`placeholder_claimed` with a `claim_source` property taking one of: `group_join`, `identity_card`,
`reconcile_screen`, `group_settings` (plus `onboarding` only if that surface turns out to exist). Fire
it in `GroupRepositoryImpl.reconcilePlaceholder` if a source can be threaded through as a parameter, or
at each of the 4 call sites if not — Claude should look at whether `reconcilePlaceholder` has one call
site per source (it does: join calls it directly; the others go through `claimPlaceholder`-style repo
methods called from the UI) and pick whichever avoids duplicating the capture call 4 times.

## 4. Scan cost/latency/model visibility — DECIDED: Option B

The data already exists (§1) in `receipt_scan_log`: model tier(s) used, escalation flag, token counts,
cost in micros, duration, and whether the reconciliation check passed.

**Decision (confirmed):** mirror `receipt_scan_log` rows into PostHog as server-side events
(`scan_model_called` / `_succeeded` / `_failed`), sent from `extract-receipt` alongside the existing DB
write, using the Node/Deno `posthog-node` SDK and `distinctId: userId`. This unlocks PostHog's LLM
observability dashboards (cost trend, p95 latency, model comparison, Haiku/Sonnet/Opus comparison) for
free, since the token/cost numbers are already computed at the exact point these calls would fire —
this is a small addition, not new instrumentation logic.

**Tradeoff accepted:** a second write path (Supabase table + PostHog event) that can drift if one side
is edited later without the other. Mitigate by writing both from the same code block in
`extract-receipt`, right next to each other, so a future edit to one is hard to miss the other.

## 5. Web claim tracking (non-app users)

Real flow, from `ClaimList.svelte`: per-item chips (`chip--claim` = "＋ I had this",
`chip--join` = "＋ Add me" → opens `JoinSheet.svelte`), a change log with per-entry undo, and a
"I'm done" dock button (`finish()`) that validates the claimer's total isn't zero before confirming.

### Performance impact of adding PostHog JS here — the actual numbers

The current claim bundle (`dist/assets/index-*.js`) is **156 KB uncompressed** — small, and served from
a **prerendered** static HTML file (`web/AGENTS.md`: `prerender.mjs` writes real markup per route, so
first paint never waits on JS at all, prerendering not hydration). This matters because it changes what
"slows down opening the link" even means here: the thing the user sees first is already static HTML,
independent of any JS bundle size.

- `posthog-js`'s full package is commonly cited around 60-80 KB gzipped for the complete feature set
  (session replay, feature flags, surveys, etc. all bundled) — I could not pull an exact current number
  from PostHog's docs or bundlephobia in this pass (both returned truncated/no data), so treat that as
  an approximate ceiling, not a verified figure.
- **The number that actually matters is not the size, it's how it's loaded.** PostHog's standard
  integration is an async `<script>` snippet: the tiny inline loader (a few hundred bytes) queues
  events and fetches the real SDK in the background, **non-blocking** — it does not delay parsing,
  rendering, or the claim page becoming interactive. Bundling `posthog-js` as an ES import into the
  Vite build (i.e., `import posthog from 'posthog-js'` at the top of `main.ts`) is the version that
  actually costs you: that adds real KB to the bundle that ships and parses before the app can mount.
- **Recommendation:** if you do this, use the async snippet in `dist/app.html` / the claim route's HTML
  shell, not a bundled import. That keeps the "click a link, see it instantly" property essentially
  intact — the prerendered content still paints first, and PostHog's own script loads and initializes
  in parallel, off the critical path. The one real cost is a second network request (DNS + TLS + fetch
  for PostHog's CDN) — on a fast connection this is low tens of milliseconds and happens *after* the
  page is already visible, not before.
- This still doesn't remove the `web/AGENTS.md` "zero runtime dependencies" architectural question —
  it's a call about maintaining a deliberate constraint, not a performance one. Confirm you want to
  cross that line before Claude adds it.

If approved, proposed events, matching the real UI rather than a generic "select an item" model:
- `claim_page_viewed` — `{ scan_id, group_id, is_app_user }`
- `claim_item_selected` — fired from `claimOne()` — `{ scan_id, item_id }`
- `claim_undone` — fired from `undoChange()` (real UI has this; PostHog's list didn't) — `{ scan_id, item_id }`
- `claim_submitted` — fired from `finish()` — `{ scan_id, item_count, is_app_user }`

### 5a. Web: does the anti-duplication design actually get used?

**Why this exists** (per the user, confirmed against `web/AGENTS.md` §"Frame 1 — recognition"): if John
creates a group with placeholders for Andrew and Stacy, and Andrew later opens the claim link and types
"Andrew" as a fresh name instead of picking the existing placeholder, the group ends up with two Andrew
identities — a real correctness problem (which Andrew does a new expense split against, which Andrew's
balance is the real one). `PickName.svelte` exists specifically to prevent this by listing only
unclaimed placeholders and asking "Been in this group before? Tap your name" before letting anyone type
one. This has never been measured.

There are **three distinct decision points** in the real code, not one, and they answer different
questions:

1. **`PickName.svelte`** (`pick()`, line 21) — guest is shown a list of existing unclaimed placeholders
   and taps "That's me" on one → `store.claimPlaceholder(userId)`. This is the design working as
   intended.
2. **`PickName.svelte`'s escape hatch** — "I'm none of these, I'm new" → routes to `name_entry` with no
   merge. Legitimate when they're genuinely new, but also the exact failure mode described above if
   their placeholder was in the list and they missed it or didn't recognize it.
3. **`NameEntry.svelte`'s `acceptSuggestion()`** (line 52) — after typing a name from scratch, the API
   fuzzy-matched it to an existing placeholder and offered it as a suggestion; accepting still merges
   via the same `store.claimPlaceholder()` call. This is the safety net catching people who skipped or
   never saw option 1.

Proposed events:
- `web_candidates_shown` — fired when `store.phase` becomes `'pick_name'` — `{ scan_id, candidate_count }`.
  Tells you what fraction of guests were even offered the chance to avoid a duplicate (guests with
  `candidates.length === 0` skip straight to `name_entry` and never had a choice — don't count them as
  "declined").
- `web_identity_resolved` — fired once, whichever path completes — `{ scan_id, outcome, source }` where
  `outcome` is `"claimed_placeholder"` or `"created_new"`, and `source` is `"pick_name_list"` (path 1),
  `"pick_name_declined"` (path 2), or `"name_entry_suggestion"` (path 3). This is the number that answers
  the actual question: of everyone who *could* have claimed an existing placeholder, how many did.
- `web_claim_race_lost` — fired when `store.claimPlaceholder()` returns `{ won: false }` (the existing
  "first claim wins" race in `store.svelte.ts:214-224`, e.g. two people both tap "That's me" on the same
  placeholder) — `{ scan_id }`. Rare, but distinguishes a genuine UX failure from ordinary usage.

Together, `web_candidates_shown` vs. `web_identity_resolved`'s `source` breakdown is the metric the user
asked for: (claims via path 1 + path 3) ÷ candidates_shown tells you how well the anti-duplication
design actually works, and a high `pick_name_declined` rate relative to `name_entry_suggestion` recoveries
would mean people are missing themselves in the list — a UX problem, not a feature nobody needs.

## 6. Session replay

### What it is, and how it differs from what you already collect

Everything in §1-5 is **discrete events**: named facts with properties (`expense_added`,
`{split_mode: "by_item"}`) — you get counts, funnels, and breakdowns, but never *what the screen looked
like* at that moment. Session replay is different in kind: it reconstructs a scrubbable, video-like
playback of what a specific person actually did — every tap, scroll, and screen transition, timestamped
— by recording DOM/UI mutations (not literal video) and replaying them client-side in PostHog's UI. It
answers "why did they drop off here," which no amount of event-property breakdown can, because a funnel
tells you *that* someone stalled at `group_created` → `expense_added` but not *what they tried instead*.

### Privacy-safe mobile replay for an expense tracker

This is the part worth being deliberate about, since your screens show real dollar amounts, real names,
and real bill contents. Two things work in your favor:

1. **Mobile replay's default mode is wireframe-only, not pixel screenshots.** PostHog's mobile SDKs
   (Android/iOS) reconstruct the screen as a schematic layout of component shapes and positions by
   default — buttons, text fields, and cards render as generic blocks, not their actual rendered pixels
   — with **no keyboard capture** at all. A "screenshot mode" exists as an opt-in for higher fidelity,
   but the safe default already avoids recording the actual receipt totals or names as pixels.
2. **Masking happens before anything leaves the device.** Sensitive fields (inputs, specific views you
   mark) are masked client-side, and PostHog is explicit that masked data is **never sent over the
   network** — it's not "captured then redacted server-side," it's excluded at the point of capture.
   For this app specifically, you'd want to explicitly mask: any amount/currency field, the receipt
   image itself, member names/handles, and the payment-handle field from onboarding (§2).

Given both of those, the practical setup for Evenly is: enable mobile replay in **wireframe mode**
(the default — do not opt into screenshot mode), and explicitly mask amount fields, names, and receipt
images even though wireframe mode already hides most of this by construction, as defense in depth.

### Free plan availability

Session replay ships on PostHog's free tier: **5,000 free web recordings/month**, and **2,500 free
mobile recordings/month** specifically (mobile has a separate, smaller free allotment and a higher
per-recording price after that — roughly double the web overage rate, per PostHog's pricing page).
Given the KMP app is Android/iOS native, you'd be drawing from the mobile quota, not the web one — worth
noting since 2,500/month caps how many drop-off sessions you can review before it starts costing money,
distinct from the 1M/month free allowance for ordinary events (§1-4's events draw from that pool
instead, so replay and event tracking don't compete for the same quota).

## 7. Status (2026-08-22)

Sections 2–5a are **implemented** — verified with `:shared:compileAndroidMain`,
`:shared:compileKotlinIosSimulatorArm64`, `:shared:testAndroidHostTest`, and (web) `npm run typecheck` +
`npm test`, all green. Notes on where implementation deviated from this doc's original sketch:

- **§3 placeholder sources collapsed from 4 to 3.** `IdentityClaimCard`'s "see all" and the full-screen
  `ReconcileScreen` are the same destination (`ReconcileRoute.kt`'s own docstring says so), not two —
  `claim_source` is `group_join` / `group_home` / `group_settings`, threaded via a new `Route.Reconcile
  .source` field rather than 4 separate call sites.
- **§4 mirrors via a direct HTTP call to PostHog's capture endpoint, not `posthog-node`.** The SDK
  batches on an interval, which risks losing events when the edge function's isolate tears down before
  the next flush; a one-shot `fetch` has no such window. New env vars: `POSTHOG_API_KEY`, `POSTHOG_HOST`
  (optional, defaults to PostHog Cloud US) — inert with neither set, same convention as
  `ANTHROPIC_API_KEY`/Slack webhooks elsewhere in this codebase.
- **§5 loads `posthog-js` via a CDN dynamic `import()` in `index.html`, never as an npm dependency** —
  `web/AGENTS.md`'s zero-runtime-dependencies rule governs the bundled Vite build, and a CDN fetch never
  enters it. Set `VITE_POSTHOG_KEY` (and optionally `VITE_POSTHOG_HOST`) to activate; unset means zero
  network cost, not a broken build.
- **§5a's properties changed from `scan_id`/`group_id` to `expense_id`.** Neither `BillHeader` nor the
  pre-bill-load resolve responses expose a scan or group id to the browser, and the bill token itself is
  a live bearer credential that must never be sent to an analytics vendor. `expense_id` (from
  `BillResponse.expense.id`) is the one safe, already-client-visible identifier, and it's only available
  once the bill has loaded — `claim_page_viewed` and `web_candidates_shown` carry no bill id at all.
- **§6 session replay: NOT release-gated.** Per owner decision, `sessionRecording.enabled = true` in
  **both** debug and release builds right now, wireframe mode only (never screenshot mode), as a
  deliberate temporary diagnostic state — the owner is pulling a real recording to manually check
  whether balances/names leak through this wrapper's limited masking before deciding whether to invest
  in per-element masking, accept the input/image-only masking that exists today, or turn it back off.
  See the comment above `sessionRecording` in `PostHogAnalytics.kt`.

## 8. Suggested execution order

1. Confirm the two open questions (§2 onboarding-claim surface, §4 Option A vs B, §5 web SDK go-ahead)
2. Onboarding step events (§2) — self-contained, no dependencies
3. Placeholder claim sourcing (§3) — self-contained
4. `expense_added.entry_method` — one-line addition, not detailed above because it's trivial: add
   `"entry_method" to (if (fromScan) "scan" else "manual")` at the existing `expense_added` capture site
5. Scan cost mirroring (§4), if Option B chosen
6. Web claim tracking (§5), if SDK addition approved

Each of 2–6 is small enough to hand to Claude as its own prompt/session, following this repo's existing
pattern of one `AnalyticsEvents.kt` entry + one `.posthog-events.json` entry per new event name.
