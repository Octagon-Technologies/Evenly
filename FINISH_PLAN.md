# ShareCost — Finish Plan (wired-with-stubs → fully functional)

> Companion to `BUILD_PLAN.md`. That plan got the layered architecture stood up and the
> 22 screens drawn + mostly wired. This plan closes the gap between **"screens render real
> data for the happy path"** and **"every feature actually works."**
>
> **How to use this:** run **one phase per session**. Each phase is scoped to a single
> coherent context window — the files it touches overlap heavily, so a fresh session can hold
> the whole thing in its head. Each phase ends with a **Kickoff prompt** you can paste to
> start that session, and an **Acceptance** checklist that defines "done."
>
> Status: ✅ done · 🔄 in progress · ⬜ not started

---

## Current state (audited 2026-06-15)

The app is **local-first on Room** and, at runtime, binds the **real `SupabaseAuthSession`
+ `SyncEngine`** (the populated Supabase config makes `isConfigured == true`, so
`StubAuthSession` is dormant). The pure domain layer (split allocator, bilateral balances,
itemized split, settlement allocation, conflict join/resolve) is **genuinely implemented and
tested**. The happy-path feeds — Home group list, Group expenses/overview/conflicts tabs,
Add/Edit expense, Expense detail core, Settle-a-person, Balances — are **wired to real
repositories**.

What's left is: (1) a handful of **screens still rendering hardcoded samples**, (2) **built
engines with zero callers** (FX conversion), (3) **real repo methods never wired to UI**
(archive / leave / rename / join-by-token), (4) **brand-new features** (comments, receipts,
real auth providers), and (5) **backend robustness** (per-write sync + push delivery).

---

## Part A — Dummy / placeholder inventory

Everything currently fake, grouped by the phase that fixes it.

### Still rendering hardcoded sample data at runtime
| Where | What's fake | File |
|---|---|---|
| Onboarding | Discards name/currency/handles; always `signIn("Alex Rivera")` | `ui/navigation/WiredScreens.kt:35` |
| Magic-link | Ignores typed email, `signIn("You")` anonymously; no Loading/Sent state; resend no-op | `ui/navigation/WiredScreens.kt:42`, `ui/screen/auth/MagicLinkScreen.kt` |
| Home cards | `members = 1`, `status = Settled`, `last = "Tap to open"` hardcoded; `archived = emptyList()` | `ui/screen/home/HomeViewModel.kt:40,61-69` |
| Archived screen | Renders `ArchivedSamples`; `onUnarchive` no-op; never navigated to | `ui/screen/home/ArchivedScreen.kt:58`, `ui/navigation/ShareCostNavHost.kt:73` |
| Join sheet | Hardcoded "Tulum Trip / 5 members"; ignores deep-link token; routes to `GroupHome("joined")` | `ui/screen/home/JoinGroupSheet.kt:48`, `ShareCostNavHost.kt:74-80` |
| Settle-single sheet | Hardcoded "Dinner at La Negra / Andrew / $24"; `onMarkPaid` records nothing | `ui/screen/settle/SettleSingleSheet.kt:42-47`, `ShareCostNavHost.kt:138-145` |
| Reconcile screen | "Demo placeholder data" people; `onConfirm` → `GroupHome("1")`, ignores claims; never navigated to | `ui/screen/reconcile/ReconcileScreen.kt:63`, `ShareCostNavHost.kt:181` |
| ReconcileConfirmModal | Demo data; **dead code** — only its own `@Preview` references it | `ui/screen/reconcile/ReconcileConfirmModal.kt:49` |
| Filter sheet | Hardcoded member list + "Show 14 results"; `onApply` ignored | `ui/screen/group/FilterSheet.kt:45,81`, `ShareCostNavHost.kt:101` |
| Search overlay | Query prefilled "tax"; hardcoded results; `onResult` no-op | `ui/screen/group/SearchOverlay.kt:45`, `ShareCostNavHost.kt:103` |
| Expense-detail comments | Two hardcoded `CommentBubble`s ("I already sent Andrew $16 in cash 🙌"); can't type; Send no-op; no repo | `ui/screen/expense/ExpenseDetailScreen.kt:186-193` |
| Expense-detail receipts | 2 fake thumbnails; "Add" `clickable {}`; no storage | `ui/screen/expense/ExpenseDetailScreen.kt:134-141` |
| Expense-detail history | Literal "Andrew added this expense · May 23, 8:40 PM" | `ui/screen/expense/ExpenseDetailScreen.kt:260` |

### Built but never called / never wired
| Capability | State | Evidence |
|---|---|---|
| **FX conversion** | Full engine (`FxRepositoryImpl` + Frankfurter fetcher) + 18 tests, **zero callers** | `data/repository/FxRepositoryImpl.kt`, registered `di/DataModule.kt:43`, no call sites |
| Expense currency | Hardcoded to group base; no picker | `ui/navigation/LedgerRoutes.kt:52,78` |
| "≈ £18.36 (rate: 0.765)" | Hardcoded string, not a real conversion | `ui/screen/settle/SettleSingleSheet.kt:84` |
| Category spend card | Always empty — no `category` field on `Expense` | `ui/screen/group/GroupTabRoutes.kt:81`, `domain/expense/Expense.kt` |
| `setArchived` / `leaveGroup` / `renameGroup` | Real repo methods + tests, **no UI invokes them** | `data/repository/GroupRepositoryImpl.kt:149-201` |
| Invite link | Real `inviteToken` exists; UI shows literal "sharecost.app/j/8Kk2-Tulum", copy/rotate no-op | `ui/screen/settings/GroupSettingsScreen.kt:124-142` |
| `joinByToken` | Resolves **local cache only**; never-synced groups return `GROUP_NOT_CACHED` | `data/repository/GroupRepositoryImpl.kt:124-131` |
| Push | `PushService` reads real FCM token, but **no host registration emits/consumes**; notification toggles local-only | `platform/PushService.kt`, `ui/screen/settings/ProfileScreen.kt:86-90` |
| Sync | 15s heartbeat full-table last-write-wins; **no realtime, no per-write outbox** | `data/remote/supabase/SyncEngine.kt`, `SupabaseAuthSession.kt:54-61` |

### Cosmetic no-ops (out of MVP scope — see Phase F8)
Legal links (Privacy/Terms), Export CSV/JSON/PDF, Storage quota, Categories management,
Delete account, Send feedback, theme switch, the literal "May 21–27" date-range chip,
remove-member rows, member-detail rows.

---

## Part B — The phased build plan

Recommended order is top-to-bottom: **F1→F4 complete the core loop**, **F5–F6 enrich**,
**F7 hardens the backend**, **F8 is optional polish**. F2/F3/F4 are largely independent of each
other and can be reordered. **Rule that cuts across phases:** any phase that adds a new DB
table (F2 category, F5 comments/receipts) must also add it to `SyncEngine.push()/pull()` in the
same session — don't leave new tables unsynced for F7.

---

### Phase F1 — Real identity: Google + Apple + Email magic-link  ⬜
**Outcome:** A user signs in with Google, Apple, or an emailed magic link / OTP, the anonymous
session is promoted to that real identity, and the profile they enter during onboarding actually
persists. No more "Alex Rivera."

**Why these belong together:** they all touch the same surface — `AuthSession` interface,
`SupabaseAuthSession`, the auth screens (`SignIn`, `Onboarding`, `MagicLink`), `WiredScreens`
route wrappers, and `ProfileRepository`. OAuth + OTP share the Supabase Auth client and the
"promote anonymous → real user, keep the local `users` row" plumbing, so doing them in one
session avoids touching that plumbing three times.

**Files:** `domain/auth/AuthSession.kt` · `data/auth/SupabaseAuthSession.kt` ·
`data/remote/supabase/SupabaseClientFactory.kt` (add providers) · `ui/screen/auth/*` ·
`ui/navigation/WiredScreens.kt` · `ui/screen/settings/ProfileScreen.kt` + Profile route ·
new `platform/WebAuthSession` expect/actual (OAuth redirect) · `androidApp`/`iosApp` host
config (URL schemes, Apple entitlement, Google client IDs).

**Tasks:**
1. Extend `AuthSession` with `signInWithGoogle()`, `signInWithApple()`, `sendEmailOtp(email)`,
   `verifyEmailOtp(token)` (or magic-link deep-link handling). Keep `signOut()`.
2. Implement them in `SupabaseAuthSession` using `client.auth` (`signInWith(Google/Apple)` +
   `linkIdentity` / OTP). Promote the existing anonymous user rather than creating a second row.
3. Add the `WebAuthSession` expect/actual + host plumbing: Android Custom Tabs redirect +
   intent filter; iOS `ASWebAuthenticationSession` + Apple Sign-In capability; Google/Apple
   client IDs in `SupabaseConfig`/host config.
4. Make `MagicLinkScreen` real: send OTP, show Loading→Sent states, working Resend, handle the
   returned deep link to complete sign-in.
5. Make `OnboardingScreen` persist: write display name, base currency, and payment handles to
   the `users` row via `ProfileRepository` instead of discarding them; stop hardcoding the name.
6. `ProfileScreen`: wire the edit-name pencil to a real update; persist notification preference
   toggles (store as a `users` column or a small prefs table — actual *delivery* lands in F7).

**Acceptance:**
- [ ] Each of Google / Apple / Email completes sign-in on a fresh install and lands on Home.
- [ ] The signed-in display name + email shown on Profile match what the provider/onboarding gave.
- [ ] Sign out → sign back in with the same provider returns the same account + groups.
- [ ] Onboarding name/currency/handles survive an app restart.

**Kickoff prompt:**
> Phase F1 from `FINISH_PLAN.md`: implement real auth (Google OAuth, Apple Sign-In, Email
> magic-link/OTP) promoting the current Supabase anonymous user, plus persist the onboarding
> profile and Profile-screen edits. Start by reading `data/auth/SupabaseAuthSession.kt`,
> `domain/auth/AuthSession.kt`, the `ui/screen/auth/*` screens and their `WiredScreens.kt`
> wrappers. Confirm the Supabase project's enabled providers before writing code.

---

### Phase F2 — Multi-currency + categories (the money model)  ⬜
**Outcome:** A user can record an expense in any currency and it's correctly converted into the
group's base currency for balances; the "Spending by category" card is populated.

**Why together:** both are *expense-model enrichments* — they edit the same files
(`Expense`/`ExpenseEntity`, `AddExpenseScreen`, the add-expense route, balance netting). FX and
category both flow expense→share→balance, so wiring them in one pass means one migration, one
set of mapper edits, one balances re-test.

**Files:** `domain/expense/Expense.kt` · `data/db/entity/ExpenseEntity.kt` (+ migration) ·
`data/repository/ExpenseRepositoryImpl.kt` · `data/repository/FxRepositoryImpl.kt` (consume it) ·
`data/db/entity/FxBakedEntity.kt` (already exists) · `ui/screen/expense/AddExpenseScreen.kt` +
`ui/navigation/LedgerRoutes.kt` · `domain/balance/BilateralBalance.kt` ·
`ui/screen/group/GroupTabRoutes.kt` (spend card) · `ui/screen/settle/SettleSingleSheet.kt` (FX line).

**Tasks:**
1. Add a currency picker to Add/Edit expense (default = group base) and stop hardcoding
   `currency = baseCurrency` in `LedgerRoutes.kt:52,78`.
2. On save, call `FxRepository.rate()` to **bake** the expense→base rate onto the expense (use
   `FxBakedEntity`), so historical balances are stable. Call `refreshIfStale()` on app start.
3. Net balances in base currency: have `observeBalances` apply the baked rate before
   `buildBilateralBalances`, OR convert shares at netting time. Keep per-currency display if you
   prefer, but ensure a single "you owe X base" figure exists.
4. Replace the hardcoded "≈ £18.36 (rate: 0.765)" in settle with a real `FxRepository` lookup.
5. Add a `category` (enum + optional subcategory) to `Expense`/`ExpenseEntity`; add a category
   picker to Add expense; aggregate spend-by-category in `GroupBalancesRoute` and pass it instead
   of `spend = emptyList()`.
6. (Optional, small) Fix the expenses-feed "you owe" display to show the viewer's remaining
   portion rather than the full amount (`GroupExpensesMapping.kt`).
7. **Add `category` (and any FX columns) to `SyncEngine` push/pull.**

**Acceptance:**
- [ ] Add a EUR expense in a USD group → balances show the correct USD-converted amount.
- [ ] The baked rate doesn't drift when FX rates later change.
- [ ] "Spending by category" shows real category totals.
- [ ] App still builds on Android + iOS sim; balance tests pass.

**Kickoff prompt:**
> Phase F2 from `FINISH_PLAN.md`: wire the already-built FX engine (`FxRepositoryImpl`,
> currently zero callers) into expense entry + balance netting, and add expense categories.
> Start by reading `FxRepositoryImpl.kt`, `ExpenseRepositoryImpl.kt`, `AddExpenseScreen.kt` +
> `LedgerRoutes.kt`, and `BilateralBalance.kt`. Add a Room migration for the new columns.

---

### Phase F3 — Complete settle & reconcile  ⬜
**Outcome:** Settling a single expense actually records a settlement, the deep-link confirm
path records, and the reconcile screen really merges placeholder identities onto a real user.

**Why together:** all three are the "money actually moves / identities merge" flows that share
`SettlementRepository` + the settle/reconcile routes. Settle-a-person is already real and is the
template to copy.

**Files:** `data/repository/SettlementRepositoryImpl.kt` · `data/repository/GroupRepositoryImpl.kt`
(new reconcile method) · `ui/navigation/SettleRoutes.kt` (add `SettleSingleRoute`) ·
`ui/screen/settle/SettleSingleSheet.kt` + `DeepLinkConfirmSheet.kt` · `ui/screen/reconcile/*` ·
`ui/navigation/ShareCostNavHost.kt`.

**Tasks:**
1. Build `SettleSingleRoute` (mirror `SettlePersonRoute`): load the share, call
   `applySettlement` for that share's remaining, drop the hardcoded "La Negra / Andrew / $24".
2. Make the NavHost `SettleExpense`/`SettleConfirm` destinations pass real args; have
   `DeepLinkConfirmSheet.onYes` record via `applySettlement`.
3. Add a `reconcilePlaceholder(groupId, placeholderUserId, realUserId)` to `GroupRepository` that
   reassigns the placeholder's shares/settlements to the real user and recomputes status.
4. Wire `ReconcileScreen` to real placeholder candidates and call the new method on confirm;
   add an entry point (e.g. from Group settings or a prompt when a placeholder matches you).
5. Delete the dead `ReconcileConfirmModal` or fold it into the real flow.

**Acceptance:**
- [ ] "Settle this" on an expense records a settlement and the balance drops accordingly.
- [ ] The deep-link confirm sheet records (not just pops).
- [ ] Claiming a placeholder name moves its debts onto your balance; placeholder disappears.

**Kickoff prompt:**
> Phase F3 from `FINISH_PLAN.md`: wire single-expense settle + deep-link confirm to
> `applySettlement`, and build real placeholder reconciliation. Start by reading the working
> `SettlePersonRoute` in `SettleRoutes.kt` as the template, then `SettlementRepositoryImpl.kt`,
> `ShareCostNavHost.kt` (SettleExpense/SettleConfirm/Reconcile destinations), and the reconcile screens.

---

### Phase F4 — Group lifecycle & membership  ⬜
**Outcome:** Archive/unarchive, leave, rename, remove-member, real invite links, and join-by-link
all work; Home cards show real metadata.

**Why together:** every item is "connect an already-implemented `GroupRepository` method to
`GroupSettingsScreen` / `HomeViewModel` / `ArchivedScreen` / `JoinGroupSheet`." Same repo, same
cluster of group-management screens — one session holds it all.

**Files:** `data/repository/GroupRepositoryImpl.kt` (mostly read-only here — methods exist) ·
`ui/screen/settings/GroupSettingsScreen.kt` + route · `ui/screen/home/HomeViewModel.kt` ·
`ui/screen/home/ArchivedScreen.kt` · `ui/screen/home/JoinGroupSheet.kt` ·
`ui/navigation/ShareCostNavHost.kt` · deep-link parsing in `App.kt`.

**Tasks:**
1. `HomeViewModel.toCard`: compute real `members` count, balance `status`, and `last` activity
   from the repo; populate the real `archived` list.
2. Wire Archived: `ArchivedScreen` streams archived groups, `onUnarchive` → `setArchived(false)`;
   add a `navigate(Route.Archived)` entry point from Home.
3. `GroupSettingsScreen`: wire Rename → `renameGroup`, Leave → `leaveGroup` (respect the
   admin-transfer rule), Remove member → repo, and a real "Add member" already exists.
4. Invite link: render the real `inviteToken`, wire Copy / Rotate / Share to real actions.
5. Join-by-link: parse the deep-link token in `App.kt`, feed it to `JoinGroupSheet` (show the
   real group preview), call `joinByToken`. (Cross-device join that needs server resolution is
   finished in F7 — here, handle the locally-resolvable + clear-error cases.)

**Acceptance:**
- [ ] Home cards show correct member count + settled/owed status.
- [ ] Archive a group → it leaves the active list, appears in Archived, can be unarchived.
- [ ] Rename + leave work; leaving as admin transfers admin per the rule.
- [ ] Copying the invite link yields a real token; opening it shows the right group.

**Kickoff prompt:**
> Phase F4 from `FINISH_PLAN.md`: wire the existing `GroupRepository` lifecycle methods
> (`setArchived`, `leaveGroup`, `renameGroup`, invite token, `joinByToken`) to the group/home
> screens, and make `HomeViewModel.toCard` compute real card metadata. Start by reading
> `GroupRepositoryImpl.kt`, `HomeViewModel.kt`, `GroupSettingsScreen.kt`, `ArchivedScreen.kt`,
> `JoinGroupSheet.kt`.

---

### Phase F5 — Comments, receipts & activity log  ✅ (2026-06-16)
> **Done:** `CommentEntity`/`ReceiptEntity`/`HistoryEventEntity` + DAOs (Room v4, destructive-migration),
> `ActivityRepository`(+Impl), Supabase **Storage** installed + `SupabaseReceiptStorage` (public `receipts`
> bucket), `ImageProcessor` expect/actual (Android downscale+JPEG, iOS JPEG re-encode), `ExpenseDetailScreen`
> rewritten (streamed comment thread + real `TextField`/Send, Coil receipt strip with pick→compress→upload,
> real history feed), history rows written on create/edit/settle/delete + comment/receipt. All 3 tables added
> to `SyncEngine` push/pull and to the server schema (`supabase/schema.sql` + applied migration). Coil singleton
> loader configured in `App.kt`. Tests: `ActivityRepositoryTest`. Green on Android + iOS.

**Outcome:** The "fake chat" on expense detail becomes a real comment thread; receipts upload
and display; the history section shows real events. (This is the heaviest phase — it adds new
tables + Supabase Storage.)

**Why together:** all three are new persisted artifacts hanging off an expense and all live on
`ExpenseDetailScreen`. They share new DAOs/entities, new repo, new sync-table coverage, and the
same screen — splitting them across sessions would mean re-loading the same context.

**Files:** new `data/db/entity/CommentEntity.kt`, `ReceiptEntity.kt`, `HistoryEventEntity.kt`
+ DAOs + `ShareCostDatabase` migration · new `CommentRepository` (+ receipts/history) ·
`data/remote/supabase/SupabaseClientFactory.kt` (install Storage) + `SyncEngine` (new tables) ·
`ui/screen/expense/ExpenseDetailScreen.kt` · `platform/FilePicker.kt` (exists) +
`platform/ImageProcessor` (new, for receipt compression).

**Tasks:**
1. Comments: entity + DAO + repo + Storage/sync; replace the hardcoded `CommentBubble`s with a
   streamed list; turn "Add a comment…" into a real `TextField`; wire Send.
2. Receipts: install Supabase **Storage** in the client factory; pick image via `FilePicker`,
   compress, upload, store URL; render real thumbnails; remove the fake ones + no-op Add.
3. History: write `HistoryEventEntity` rows on expense create/edit/settle; render the real feed;
   replace the literal "Andrew added this expense…".
4. Decide refunds: implement minimally or explicitly defer (note it in the doc).
5. **Add the three new tables to `SyncEngine` push/pull.**

**Acceptance:**
- [ ] Posting a comment persists, syncs, and appears on another device.
- [ ] Attaching a receipt uploads to Storage and renders from its URL.
- [ ] The history section reflects real create/edit/settle events.

**Kickoff prompt:**
> Phase F5 from `FINISH_PLAN.md`: replace the hardcoded comments/receipts/history on
> `ExpenseDetailScreen` with real persisted features. Add `CommentEntity`/`ReceiptEntity`/
> `HistoryEventEntity` + DAOs + a Room migration, a comment/receipt repository, install Supabase
> Storage in `SupabaseClientFactory.kt`, and extend `SyncEngine`. Start by reading
> `ExpenseDetailScreen.kt:130-200`, `ShareCostDatabase.kt`, and `SyncEngine.kt`.

---

### Phase F6 — Real search & filter  ✅ (2026-06-16)
> **Done:** `SearchOverlay` rewritten to stream real expense (title) + member (name) matches → tap opens the
> expense; `SearchRoute` wires it. `FilterSheet` rewritten around a `GroupFilter` (paid-by / category / date
> range) with a live "Show N results" count; `FilterRoute` + a `GroupFilterStore` (Koin singleton) carry the
> filter across the Filter↔tab destination round-trip; `GroupExpensesRoute` applies it (with a "Filtered ·
> Clear" chip + no-match empty state). The existing Active/All/Settled sub-tab now actually filters by status.
> NavHost points at the new routes. Pure `applyFilter` is unit-testable. Green on Android + iOS.

**Outcome:** The search overlay searches real expenses/members; the filter sheet actually filters
the expenses feed.

**Why together:** both operate on the same group-expenses data set and feed the same
`GroupExpensesTab`. Small, self-contained session (could be folded into F4 if you want fewer sessions).

**Files:** `ui/screen/group/SearchOverlay.kt` · `ui/screen/group/FilterSheet.kt` ·
`ui/screen/group/GroupExpensesTab.kt` + `GroupTabRoutes.kt` · maybe a `searchExpenses` query on
`ExpenseDao`.

**Tasks:**
1. Search: stream real results from a repo/DAO query over expense titles + members; wire
   `onResult` to navigate to the expense; drop the prefilled "tax" + hardcoded rows.
2. Filter: lift filter state to the route, apply it to the expenses feed (member, date range,
   category from F2, status); make Apply/Reset real; replace the literal "Show 14 results" count.

**Acceptance:**
- [ ] Typing a query returns matching real expenses; tapping one opens it.
- [ ] Applying a member/category/date filter actually narrows the feed; the count is real.

**Kickoff prompt:**
> Phase F6 from `FINISH_PLAN.md`: make search + filter real over the group expenses feed.
> Start by reading `SearchOverlay.kt`, `FilterSheet.kt`, `GroupExpensesTab.kt`, `GroupTabRoutes.kt`,
> and `ExpenseDao`.

---

### Phase F7 — Sync hardening & push delivery (S-1++)  ✅ (2026-06-16, core)
> **Done:** Realtime installed; `SyncManager` replaces the 15s heartbeat with **push-on-write** (Room
> `invalidationTracker` → debounced `push()`), **near-real-time pull** (Supabase Realtime postgres-changes →
> debounced `pull()`), and a 60s safety-net full sync (which also drains offline edits on reconnect).
> Server-resolve join-by-token via `RemoteGroupGateway`(+Supabase impl) — a never-synced group now joins
> (`GROUP_NOT_CACHED` → real lookup; only a true miss is `GROUP_NOT_FOUND`). Push: Android
> `ShareCostMessagingService` + manifest + `firebase-messaging` dep forward FCM into `PushBus`;
> `PushController` registers the token to `device_tokens` and pulls on each delivered message; Android 13+
> notification permission requested in `MainActivity`. `device_tokens` table + realtime publication added to
> the schema + applied. Tests: server-resolve join (fake gateway). Green on Android + iOS.
>
> **Deferred (documented):** the *server-side* push **sender** (edge function targeting `device_tokens`) and
> the iOS Firebase host SDK (CocoaPods/SPM + `GoogleService-Info.plist`) are out of client scope — the
> iOS→`IosPushTokenHolder` bridge is ready for them. Profile notification toggles still hold local state
> (persistence belongs on DataStore per 06 §2.2, not yet wired; and gates only the future sender).

**Outcome:** Writes sync promptly and reliably (not on a 15s heartbeat), changes arrive in near
real-time, cross-device join works, and push notifications actually fire and update the app.

**Why together:** these are the backend-robustness items that all touch `SyncEngine`,
`SupabaseAuthSession`, the new sync tables, and `PushService` host wiring — the deepest layer,
best done once the data model is final (after F2/F5 add their tables).

**Files:** `data/remote/supabase/SyncEngine.kt` + new `pending_mutations`/`sync_state` tables ·
`data/remote/supabase/SupabaseClientFactory.kt` (install Realtime) · `SupabaseAuthSession.kt`
(drop the heartbeat) · all repos (enqueue on write) · `platform/PushService.kt` + Android
`FirebaseMessagingService` + iOS `AppDelegate`/`MessagingDelegate` host wiring ·
`ui/screen/settings/ProfileScreen.kt` (toggles → real subscriptions).

**Tasks:**
1. Add a `pending_mutations` outbox + `sync_state`; repos enqueue on write; a worker drains it
   (push-on-write) instead of the periodic full-table push.
2. Install Supabase **Realtime**; subscribe to the user's groups for near-real-time pull.
3. Server-resolve `joinByToken` so a never-synced group can be joined (fixes `GROUP_NOT_CACHED`).
4. Push: register the Android `FirebaseMessagingService` + iOS notification delegate, feed the
   token into `PushBus`, consume incoming messages to refresh/notify; request notification perms.
5. Wire the Profile notification toggles to real topic/subscription state.

**Acceptance:**
- [ ] A write on device A appears on device B within seconds (no 15s wait).
- [ ] Joining a group you've never synced works.
- [ ] A push notification arrives on a real device and deep-links into the relevant screen.
- [ ] Offline edits queue and drain on reconnect.

**Kickoff prompt:**
> Phase F7 from `FINISH_PLAN.md`: replace the 15s full-table heartbeat with a real per-write
> outbox + Supabase Realtime, server-resolve join-by-token, and wire push notification delivery
> end-to-end. Start by reading `SyncEngine.kt`, `SupabaseAuthSession.kt`, `SupabaseClientFactory.kt`,
> `platform/PushService.kt`, and the host app modules.

---

### Phase F8 — Polish & cosmetics (optional)  🔄 (2026-06-16, partial)
> **Done:** Profile → Privacy policy / Terms of service / Send feedback now open real URLs / a mailto via
> `UrlOpener` (were dead rows). **Deferred (v1.1+ per spec — left visible but not yet functional):** theme
> switch, CSV/JSON/PDF export, delete account, storage/categories management, member-detail rows.

**Outcome:** The remaining decorative no-ops either work or are honestly hidden.

**Tasks (pick what matters for launch):** Privacy/Terms links → real URLs · Export CSV/JSON ·
theme switch (light/dark) · Delete account · Send feedback · member-detail rows · real
date-range chip on Overview · Storage/Categories management. Several of these are explicitly
v1.1+ in the spec — hide rather than ship half-done if you're time-boxed.

---

## Dependency / ordering notes
- **F1 first** — nothing else is trustworthy without a real account identity per device.
- **F2, F3, F4 are independent** of each other; do them in any order after F1.
- **F2 before F6** — filtering by category needs the category field F2 adds.
- **F5 before/with F7** — F5 adds tables that F7's sync must cover (or wire them in F5 and let
  F7 just upgrade the transport).
- **F7 last** — the transport rework is easiest once the schema has stopped changing.

## "Fully functional" when
- [ ] Sign in with Google / Apple / Email; profile persists (F1)
- [ ] Add expenses in any currency; balances convert; categories tracked (F2)
- [ ] Settle a person AND a single expense; reconcile placeholders (F3)
- [ ] Create / join / archive / leave / rename groups; manage members; real invite links (F4)
- [x] Comment on and attach receipts to expenses; real history (F5)
- [x] Search and filter the expense feed (F6)
- [x] Real-time multi-device sync (F7); push delivery client-wired (server sender = future)
