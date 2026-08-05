# Plan A — Server-side scan cost ledger

**Session type:** backend / Supabase. Runs in parallel with Plan B
(`ANALYTICS_PLAN_B_CLIENT_FUNNEL.md`). Read §"File ownership" before touching anything.

## Goal

Make `receipt_scan_log` the authoritative record of what every receipt scan **cost us**, so the free
allowance in the coming freemium tier can be calibrated from measured data instead of guesswork.

Today the row is `(id, user_id, created_at)`. It proves a scan happened and nothing else: not which
model served it, not how many pages, not whether it succeeded, not a single token. Meanwhile
`extract-receipt` makes 1-2 paid Anthropic calls per scan and discards the `usage` block on every
response.

**Why server-side:** pricing decisions must never rest on client analytics. Client events are dropped
by tracker blockers, lost offline, and trivially spoofed. PostHog answers "what did people do";
this ledger answers "what did it cost", and only one of those can be allowed to be wrong.

## File ownership

**You own, exclusively:**

- `supabase/schema.sql`
- `supabase/functions/extract-receipt/index.ts`
- `supabase/AGENTS.md` (only if a convention note is warranted)

**Do NOT touch** anything under `code/` — that is Plan B's session, and a concurrent edit there will
collide. In particular do not edit `ReceiptOcrHttp.kt` even though it is the caller of your endpoint.
The cross-session contract below is designed so you never have to.

## Cross-session contract (both plans code against this independently)

Two optional fields on the `extract-receipt` wire format. Each side degrades gracefully, so **neither
plan blocks the other and either may land first.**

| Direction | Field | Owner | Consumer behaviour if absent |
| --- | --- | --- | --- |
| request body | `groupId: string \| null` | Plan B sends it | **You** read `payload.groupId ?? null` and store null |
| response body | `scanId: string` | **You** return it | Plan B reads it as nullable and omits the property |

Your obligations: accept an unknown-but-optional `groupId`, never fail a request that omits it, and
add `scanId` (the `receipt_scan_log.id` you already insert) to every success **and** every error
response you can attach it to.

## Steps

### 1. Widen the ledger table (`supabase/schema.sql`)

Additive, idempotent, `add column if not exists` per the rules in `supabase/AGENTS.md`. All new
columns nullable, because the row is inserted *before* the paid call and only completed afterwards.

Columns to add to `public.receipt_scan_log`:

- `group_id uuid` — nullable, references the group when the client supplied it. Decide the FK
  behaviour deliberately: `on delete set null` keeps cost history after a group is deleted, which is
  what you want for a usage ledger.
- `page_count int` — pages submitted (the endpoint already caps at 8).
- `outcome text` — one of `ok`, `not_receipt`, `invalid_draft`, `failed`, `rate_limited`,
  `breaker_open`. Add a CHECK constraint so a typo cannot silently create a new bucket.
- `tiers_used text[]` — every tier that actually ran, in order. An array, not a scalar: an escalated
  scan genuinely consumed two models and both cost money.
- `input_tokens int`, `output_tokens int` — summed across all tiers of the scan.
- `cost_micros bigint` — computed cost in millionths of a dollar. Integer, never float.
- `duration_ms int` — wall clock for the whole scan, for the latency/abandonment story.
- `completed_at timestamptz` — null means the function died mid-scan, which is itself a finding.

Keep RLS exactly as it is (`own scan log`, `for all to authenticated`, `user_id = auth.uid()`). The
edge function writes with the caller's client today; confirm the widened update still passes the
policy, or move the completion update to the service role.

**Consider:** a `receipt_scan_cost_daily` view aggregating cost per user per day. It costs nothing to
add and it is the query you will actually run every week.

### 2. Capture usage in the edge function (`supabase/functions/extract-receipt/index.ts`)

- Keep the existing insert-before-the-paid-call exactly as it is. It is load-bearing for the
  rate limit (it closes the check-then-act race and it fails closed), and the comment at that site
  explains why. **Capture the inserted row's `id`** by adding `.select("id").single()` so you can
  complete the row later and return `scanId`.
- Per tier in the cascade loop, accumulate `response.usage.input_tokens` and
  `usage.output_tokens`, and push the tier name onto the tiers array. Do this for **every** tier that
  ran, including one that returned `is_receipt: false` — it cost money regardless of verdict.
- Price the tokens with a per-model rate table declared as a `const` at the top of the file next to
  the existing `TIERS`, with a comment recording the date the rates were checked. Rates change;
  a stale hardcoded number that nobody can find is worse than no number.
- Update the ledger row at every exit path: success, `is_receipt: false`, invalid draft after all
  tiers, breaker open, and the catch-all error handler. Use a single `finally`-style helper so a new
  early return added later cannot silently skip it.
- **The completion update must never fail the request.** A ledger write error gets logged and
  swallowed; the user still gets their receipt. This is the opposite of the pre-call insert, which
  correctly fails closed. Be explicit about the asymmetry in a comment or someone will "fix" it.
- Instrument the two blocks you already perform but currently swallow: the 20-scans-per-hour
  429 and the Opus circuit breaker. Write a ledger row with the matching `outcome` so
  "how often do we say no" becomes answerable. You are already turning users away and have no idea
  how often.
- Read `payload.groupId` if present (see the contract) and store it on the row.
- Add `scanId` to the JSON response shape.

### 3. Verify

- Apply the schema change via the dashboard SQL editor or Supabase MCP `apply_migration`, then
  re-run it to prove idempotence.
- Exercise the function against a real receipt and confirm one row per scan with plausible token
  counts. Cross-check `cost_micros` against the Anthropic console for the same window; if they
  disagree, your rate table is wrong, not the console.
- Force each outcome deliberately: a non-receipt photo, a deliberately unreadable image, and a
  rate-limit trip. Confirm every one produces exactly one row with the right `outcome`.
- Confirm a scan with **no** `groupId` in the body still succeeds (Plan B may not have landed yet).

## Done when

- [ ] Every scan writes exactly one ledger row, with token counts, tier list, cost, and outcome.
- [ ] Blocked scans (rate limit, breaker) are recorded rather than silently discarded.
- [ ] A ledger write failure can never fail a user's scan; a pre-call insert failure still can.
- [ ] `scanId` is on the response; a missing `groupId` in the request is handled without error.
- [ ] Schema change is additive and re-runnable.
- [ ] You can answer, with one SQL query: scans per user last 30 days, cost per user, and the
      escalation rate. Those three numbers set the free tier.
