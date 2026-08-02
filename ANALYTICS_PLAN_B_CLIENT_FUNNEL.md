# Plan B — Client scan funnel and event hygiene

**Session type:** KMP client (`code/`). Runs in parallel with Plan A
(`ANALYTICS_PLAN_A_SERVER_LEDGER.md`). Read §"File ownership" before touching anything.

## Goal

Make the app's behavioural analytics answer the questions that decide the pricing model. Three gaps
today:

1. **Scanning emits nothing.** The single action that costs real money has zero events. There is no
   `scan_started`, no `scan_completed`, no `scan_failed`. The funnel matters more than the count,
   because an abandoned scan still cost us an Anthropic call.
2. **No event carries a `group_id`.** Everything is user-scoped. Group-unlock pricing lives or dies
   on group-level conversion, and that analysis is impossible with the current event shape.
3. **Event names are bare string literals** scattered across four repository files, with no
   compile-time protection against drift or typos.

## File ownership

**You own, exclusively:**

- `code/shared/src/commonMain/kotlin/app/splitevenly/platform/Analytics.kt`
- `code/shared/src/commonMain/kotlin/app/splitevenly/platform/PostHogAnalytics.kt`
- new `code/shared/src/commonMain/kotlin/app/splitevenly/platform/AnalyticsEvents.kt`
- the four repositories under `.../data/repository/` (`Group`, `Expense`, `Bill`, `Settlement`)
- `.../data/auth/SupabaseAuthSession.kt`
- the scan path: `.../data/remote/supabase/ReceiptOcrHttp.kt`, `.../domain/receipt/ReceiptOcr.kt`,
  `.../ui/screen/bill/BillScanState.kt`, and the scan call sites in `AddExpenseScreen.kt` /
  `BillEditScreen.kt` / `BillRoutes.kt`
- `.posthog-events.json` (the existing event catalogue at the repo root)

**Do NOT touch** anything under `supabase/` — that is Plan A's session. You never need to: the
contract below is deliberately one-directional on each field.

## Cross-session contract (both plans code against this independently)

| Direction | Field | Owner | Your behaviour |
| --- | --- | --- | --- |
| request body | `groupId: string \| null` | **You** send it | Add it to the `extract-receipt` POST body; harmless if the server ignores it |
| response body | `scanId: string` | Plan A returns it | Parse as **nullable**, attach as a property when present, omit when not |

Neither side blocks the other and either may land first. Do not make `scanId` a required field in
your response model or you will break scanning until Plan A deploys.

## Steps

### 1. Event catalogue (`AnalyticsEvents.kt`)

One object of `const val` names. Move the twelve existing literals into it and update the call sites.
Nothing else changes about how they fire.

Also settle a naming convention now and write it in a header comment: `object_verb_past_tense`
(`group_created`, `scan_completed`), snake_case, no screen names in event names. The existing twelve
already follow this; codify it before the next twenty arrive.

### 2. Scan funnel

The scan flow already has the states you need (`ScanUiState.Working/Failed/Idle`, `PickSource.
Photos/Files/Camera`, the per-page progress). Emit:

- `scan_source_chosen` — `{ source }`. Fires at the picker, before any cost is incurred. The gap
  between this and `scan_started` is your "changed my mind at the file picker" rate.
- `scan_started` — `{ page_count, source, group_id }`.
- `scan_completed` — `{ page_count, item_count, duration_ms, group_id, scan_id? }`.
- `scan_failed` — `{ kind, page_count, duration_ms, group_id, scan_id? }`. `kind` should reuse the
  existing failure taxonomy in `ScanUiState.Failed` rather than inventing a parallel one.
- `scan_cancelled` — user hit cancel mid-scan. Money spent, value zero. This is the event most
  likely to change the product.
- `scan_result_edited` — `{ items_changed, items_total }`, fired when the user leaves the bill
  editor after a scan. This is the trust metric: a scan where every line was corrected is a scan
  that did not work, no matter what the success rate says.
- `scan_blocked` — `{ reason }` for the 429 rate limit and any server refusal. You are already
  being turned away by the server; record it client-side too, because it is a UX event as much as a
  server event.

Then `split_approach_chosen` — `{ approach }` at the Divide vs. Itemize chooser. This is the single
cheapest measure of how much anyone actually wants the expensive feature.

### 3. Group dimension

Attach `group_id` to every group-scoped event: `expense_added/edited/deleted`, `bill_created`,
`settlement_applied`, `placeholder_added`, `group_joined/left`, and the whole scan funnel.

Add `member_count` to `group_created` and `item_count` is already on `bill_created`.

**Then investigate PostHog group analytics before committing to it.** The wrapper in use is
`io.github.samuolis.posthog` (an unofficial KMP wrapper delegating to the native SDKs), and it may
not expose `group()` / `groupIdentify()` / `register()`. Check the wrapper's actual API surface
first. If group analytics is unavailable, the fallback is a plain `group_id` property on every event
plus a periodic `group_snapshot` event carrying `{ group_id, member_count, expense_count, age_days }`
— less elegant, and enough to compute group-level conversion. Do not extend `EvAnalytics` with
methods the wrapper cannot implement on both platforms.

### 4. Privacy rules, written down

Add them as a header comment on `EvAnalytics` so they survive the next contributor:

> Never send amounts, member names, emails, group names, expense titles, or receipt contents.
> Counts, enums, durations, and opaque IDs only.

The current twelve events already comply (`item_count`, `split_mode`). Codify it before someone adds
`total` to `expense_added`.

While you are in `PostHogAnalytics.kt`: session recording is configured. On an app that displays
people's financial balances, either mask aggressively or turn it off. Make that an explicit decision
rather than a default you inherited.

### 5. Update `.posthog-events.json`

The repo already keeps a catalogue of every event with a description and its source file. Every event
you add goes in it. Treat a missing entry as an incomplete change.

## Verify

```bash
cd code && ./gradlew :shared:compileKotlinIosSimulatorArm64
```

```bash
cd code && ./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug
```

Then run the app and confirm events land in PostHog Live Events:

```bash
code/iosApp/run-ios-sim.sh
```

Walk one full scan end to end and check the funnel arrives in order with a consistent `group_id`.
Then walk a cancelled scan and a failed scan.

Two repo-specific gotchas: the `em-dash-guard` hook rejects em dashes in Kotlin string literals, and
`@Volatile` in `commonMain` must be `kotlin.concurrent.Volatile`. Also note the repositories take
`analytics: EvAnalytics?` as a **nullable** constructor param (null in unit tests, real in DI) — keep
new call sites null-safe or you will break the JVM test suite.

## Done when

- [ ] All event names live in `AnalyticsEvents.kt`; no bare literals remain.
- [ ] The full scan funnel fires, including cancellation and failure.
- [ ] Every group-scoped event carries `group_id`; group-level analysis is possible.
- [ ] `scan_id` is attached when the server provides it and absent without error when it does not.
- [ ] Privacy rule is documented on the facade and no event carries money or names.
- [ ] `.posthog-events.json` lists every new event.
- [ ] iOS compile, JVM tests, and Android assemble are all green.
