# 04 — API & Sync

> Contract surface the client talks to, plus the local-first sync engine that backs it. Every mutation specified in `03-business-rules.md` MUST flow through one of the RPCs or Edge Functions listed here. Direct table mutations from the client are forbidden for anything other than reads — RLS is configured to deny them.

---

## 1. Authentication

### 1.1 Providers (v1)

- **Google** (OAuth via Supabase Auth, web flow with PKCE; both platforms).
- **Apple** (Supabase Auth + native `Sign in with Apple` on iOS; OAuth web flow on Android).
- **Facebook** (OAuth via Supabase Auth, web flow with PKCE).
- **Email magic link** (Supabase Auth `signInWithOtp`, deep-link callback).

Apple is shipped from day 1 (required by App Store rules if any third-party social provider is offered).

### 1.2 Sign-in flow (mobile)

1. User picks a provider.
2. Client launches `supabase.auth.signInWith(...)` — a `CustomTabs` (Android) or `ASWebAuthenticationSession` (iOS) opens.
3. Provider redirects to `sharecost://auth-callback?code=...` (registered URL scheme).
4. Client exchanges the code for a session; stores `access_token` + `refresh_token` in platform secure storage (Android EncryptedSharedPreferences, iOS Keychain).
5. On first session, the Supabase `auth.users → users` trigger has already created the mirror row; client fetches `users` and routes to onboarding (`05 §1`) if `payment_app_handles` is empty.

### 1.3 Session lifecycle

- Access token refreshed automatically by `supabase-kt`.
- On 401 from any RPC, client triggers refresh; on refresh failure, route to sign-in (preserving the deep-link target).
- Sign out: revoke session server-side AND wipe local Room DB (`05 §13.4`). Wiping is necessary because Room contains data scoped to that user; leaving it would leak into the next account on the device.

### 1.4 Account deletion

A dedicated RPC `delete_account()`:

1. Soft-deletes the user's `users` row.
2. Leaves all `members`, `expenses`, `shares` etc. intact (other group members' data is unaffected).
3. Removes the user from all active groups (`status = 'LEFT'`) — auto-transfers admin per `03 §7.5`.
4. Deletes `payment_app_handles`, `device_push_tokens`, `notification_preferences`.
5. Anonymizes `users.display_name` to `"Deleted user"`, `email` to `null` (after removing the `NOT NULL`-via-CHECK constraint by setting `is_placeholder = true` is **not** done — instead the constraint is dropped to allow real-user rows with NULL email after deletion).

Implementing agents MUST add a `deleted_self_at timestamptz` column to `users` to flag this state and adjust the CHECK accordingly.

## 2. RPC surface

All RPCs are Postgres functions (`SECURITY DEFINER` where they need to bypass RLS for cross-table operations, otherwise `SECURITY INVOKER`). Inputs are JSON. Outputs are typed.

### 2.1 Naming conventions

- Snake_case.
- Verb-noun: `add_expense`, `apply_settlement`, `claim_placeholders`.
- Each RPC returns a typed result row (success case) or raises a `RAISE EXCEPTION` with a structured message.

### 2.2 Error model

All RPC errors use a structured form:

```
RAISE EXCEPTION USING
  ERRCODE = 'P0001',
  MESSAGE = json_build_object(
    'code', 'STORAGE_CAP_EXCEEDED',
    'message', 'Group has reached the 1 GB hard cap.',
    'details', json_build_object('limit_bytes', 1073741824, 'used_bytes', ...)
  )::text;
```

Client parses `error.message` as JSON and uses `code` for branching. Codes (v1, expand as needed):

| Code | Meaning |
|---|---|
| `NOT_AUTHENTICATED` | No valid session. |
| `NOT_MEMBER` | Caller is not an active member of the relevant group. |
| `NOT_ADMIN` | Operation requires admin role (e.g., `rotate_invite_token`). |
| `NOT_AUTHORIZED` | Caller is a member but not the specific actor allowed (e.g., deleting another member's receipt or comment). |
| `VALIDATION` | Inputs failed validation (with `details` carrying field errors). |
| `STORAGE_CAP_EXCEEDED` | Group hit the 1 GB hard cap. |
| `STORAGE_CAP_WARN` | Group crossed 500 MB; returned as a non-fatal soft signal in the response (not an exception). |
| `RECEIPT_COUNT_EXCEEDED` | 10-receipt-per-expense limit. |
| `CONFLICT_ALREADY_RESOLVED` | Concurrent resolution. |
| `STALE_ROW` | Optimistic concurrency: `row_version` did not match. |
| `PLACEHOLDER_NAME_TAKEN` | Adding a placeholder with a duplicate (case-insensitive) name in the group. |
| `PAYMENT_OVERALLOCATED` | Settlement amount > selected shares' total. |
| `REFUND_OF_REFUND` | Attempt to refund a refund. |
| `ABANDONED_GROUP` | Mutation attempted on a group with no admin / no active members. |

### 2.3 RPC catalog

A complete list. Inputs are abbreviated; the canonical JSON shapes are derived mechanically from the Kotlin data classes in `commonMain/api/`.

#### Identity & profile

- `update_profile(display_name?, avatar_url?, base_currency?) → user`
- `set_payment_handle(app, handle, is_primary) → payment_app_handle`
- `delete_payment_handle(handle_id) → void`
- `update_notification_preferences(prefs) → notification_preferences`
- `register_push_token(platform, token, app_install_id, locale?) → device_push_token`
- `unregister_push_token(token) → void`
- `delete_account() → void`

#### Group lifecycle

- `create_group(name, emoji?, base_currency?) → group` — when `base_currency` is omitted, the server sets it to the **creator's `users.base_currency`** (D-27, OQ-04), falling back to `USD` only if that is somehow unset.
- `update_group(group_id, name?, emoji?, base_currency?, reminder_cadence?) → group` — changing `base_currency` affects display rollups only; no expense is re-denominated (OQ-12). Surface the footnote in `05 §9`.
- `rotate_invite_token(group_id) → group` — **admin only** (D-12, OQ-01). Non-admin callers receive `NOT_ADMIN`. Invalidates the previous token and stamps `invite_token_rotated_at`.
- `join_group_by_token(token) → { group, placeholder_candidates: placeholder_summary[] }` — RPC also creates the `members` row if not already present; `placeholder_candidates` is empty for the happy path.
- `leave_group(group_id) → void`
- `set_archive(group_id, archived: bool) → void`
- `claim_placeholders(group_id, placeholder_ids[]) → { merged_expense_ids: uuid[] }`
- `add_placeholder_participant(group_id, display_name) → user`
- `rename_placeholder(user_id, new_display_name) → user`
- `remove_member(group_id, user_id) → void` — any active member can call; triggers retro-undo if the removed member was added retroactively (see `03 §8.6`).

#### Expenses

- `add_expense(payload) → expense` — full creation with shares in one transaction. `payload` carries `tax_subunits`, `tip_subunits`, `tip_split_mode` when `has_tax_row` is set. If `payload.draft_id` is present, the RPC reuses it as the new `expense_id`, re-points draft-attached receipts to the expense, and soft-deletes the draft (`03 §3.5`).
- `edit_expense(expense_id, expected_row_version, payload) → expense` — optimistic concurrency. Payload includes the tax/tip fields.
- `delete_expense(expense_id, expected_row_version) → void`
- `add_refund(of_expense_id, payload) → expense` — payload mirrors `add_expense` plus `refund_of_expense_id`.

#### Drafts (D-24, OQ-13)

- `save_draft(group_id, draft_id, payload_json) → draft` — upsert by `draft_id` (client-generated UUIDv7); owner-only; never validates `payload_json`. Used for auto-save.
- `delete_draft(draft_id) → void` — soft-deletes the draft and cascade-soft-deletes its attached receipts (reclaiming Storage against the cap).

#### Settlement

- `apply_settlement(payload) → settlement` — payload includes `allocations[]`.
- `void_settlement(settlement_id, reason?) → void`

#### Conflicts

- `resolve_conflict(conflict_id, decision, new_share_subunits?) → void` — for `INCLUDE`, `new_share_subunits` is required; for `DISMISS`, omitted.

#### Comments

- `add_comment(expense_id, body) → comment`
- `resolve_comment(comment_id, resolved: bool) → comment`
- `delete_comment(comment_id) → void` — only the author can delete; soft-delete.
- `mark_thread_read(expense_id) → void` — upserts `comment_read_state`.

#### Categories

- `add_category(group_id, name, icon, display_order?) → category`
- `update_category(category_id, ...) → category`
- `delete_category(category_id) → void`
- `add_subcategory(category_id, name, icon?, display_order?) → subcategory`
- `update_subcategory(...)` / `delete_subcategory(...)`

#### Receipts

- `request_receipt_upload({ expense_id? , draft_id?, mime_type, byte_size, thumb_byte_size }) → { upload_url, thumb_upload_url, storage_path, thumb_storage_path, receipt_id, warn?: 'STORAGE_CAP_WARN' }` — exactly one of `expense_id`/`draft_id` MUST be set (XOR, `02 §3.10`). Pre-flights the cap against `byte_size + thumb_byte_size`, returns signed PUT URLs for both the compressed full image and the thumbnail (D-22, OQ-11). `byte_size` is the **compressed** size.
- `confirm_receipt_upload(receipt_id) → receipt` — client calls after BOTH uploads complete; server stats both objects in Storage (existence + size match) and creates the row; raises `VALIDATION` if either is missing.
- `delete_receipt(receipt_id) → void` — **author-only** (D-26, OQ-06): requires `uploaded_by = auth.uid()`, else `NOT_AUTHORIZED`. Soft-deletes the row and schedules both Storage objects for removal.
- `mint_receipt_url(receipt_id) → { url, thumb_url, expires_at }` — signed view URLs (full + thumbnail), TTL 1 hour. Predicate matches the receipts SELECT policy (active member for expense receipts; owner for draft receipts).

#### Retro add (compound)

- `add_member_retro(group_id, user_id, scope: 'PAST' | 'FUTURE_ONLY') → { silent_resplits: int, conflicts_created: int, auto_refunds_generated: int }`

#### Exports

- `export_group(group_id, format: 'CSV' | 'JSON' | 'PDF') → { artifact_url, expires_at }`

## 3. Edge Functions

Server-side scripted logic that runs outside Postgres (Deno runtime in Supabase). Used for things RPCs can't do cleanly (external HTTP, push fanout, file munging).

### 3.1 `refresh_fx_rates` (scheduled, daily 03:00 UTC)

- GET `https://api.frankfurter.app/latest?base=USD`.
- Upsert into `fx_rates` for `rate_date = today UTC`.
- No-op if today's row already exists.
- Returns count inserted; logs failures to Supabase logs (no user-visible side-effect).

### 3.2 `notify_admin_of_conflicts` (scheduled, every 6 hours)

Per `03 §8.5`. Queries groups with open conflicts, a non-NULL `admin_user_id`, and `last_conflict_reminder_at` older than `reminder_cadence` (or NULL); sends push via `dispatch_push`; then stamps `groups.last_conflict_reminder_at = now()` to dedup.

### 3.3 `dispatch_push` (invoked by Postgres triggers and other Edge Functions)

Inputs: `user_ids[]`, `notification_type`, `payload`. Reads `notification_preferences` and `device_push_tokens`. For Android tokens, posts to FCM HTTP v1; for iOS tokens, posts to FCM (with APNs config for token transport) or APNs directly via `node-apn`-equivalent in Deno.

Trigger fan-out from Postgres uses `pg_net` to invoke `dispatch_push` asynchronously (no blocking of the originating transaction).

### 3.4 `export_group` (on-demand)

Invoked by the `export_group` RPC. Builds the artifact via streaming SQL queries, writes to the `exports` private Storage bucket, returns a signed URL with 24h TTL.

### 3.5 `seed_default_categories` (invoked by `create_group`)

Inserts the default category tree (`02 §3.6`).

## 4. Realtime

### 4.1 Subscriptions

For the **currently open group**, the client subscribes to Postgres CHANGES on these tables filtered by `group_id = G`:

- `expenses`
- `shares`
- `settlements` + `settlement_allocations`
- `comments`
- `history_events`
- `conflicts`
- `members`
- `categories` + `subcategories`
- `drafts` (filtered to `owner_user_id = self` — own drafts only, for cross-device sync)

For the **home screen**, a single channel subscribes to `members` filtered to the user's own rows (so new group additions appear immediately).

### 4.2 Connection model

- One `RealtimeChannel` per group (joined when the group opens, closed when it leaves).
- One global `RealtimeChannel` for own membership.
- Reconnect with exponential backoff (`supabase-kt` handles this; the app exposes a small "Reconnecting…" indicator in the top bar).

### 4.3 What Realtime is NOT used for

- **Notifications** — those go via FCM/APNs (Realtime only fires when the app is foregrounded with a connection).
- **FX refresh** — pull on cold start.
- **Receipts file content** — Realtime emits the `receipts` row insert; the file itself is fetched via `mint_receipt_url`.

## 5. Frankfurter / FX

### 5.1 Endpoints used

- `GET https://api.frankfurter.app/latest?base=USD` — latest rates.
- `GET https://api.frankfurter.app/<date>?base=USD` — historical rates (used by the export PDF to stamp historical conversions).

### 5.2 Client-side fetch

On cold start: if `fx_rates` latest row's `rate_date < today UTC`, fetch latest. Run on a background dispatcher; 5s timeout; do not surface failure.

### 5.3 Baked snapshot

A JSON file at `composeApp/src/commonMain/resources/fx_baked.json` containing rates for the build date. Generated at CI time by a script `tools/regenerate_fx_baked.kts`. On every release build, the snapshot is no more than 24h old at tag time.

## 6. Sync engine (local-first)

This is the most error-prone part of the app. Implement carefully.

### 6.1 Architecture

```
┌────────────┐   write    ┌──────────────────┐  enqueue   ┌──────────────────┐
│ UI/VM      │──────────▶│ Local Repository │──────────▶│ pending_mutations│
└────────────┘            │ (Room writes)    │            └────────┬─────────┘
       ▲                  └─────────┬────────┘                     │ background
       │                            │                              ▼
       │  reads (Flow)              │           pull RPC      ┌──────────────────┐
       └────────────────────────────┘◀──────────────────────│ Sync worker      │
                                                              │ (singleton coro) │
                                                              └────────┬─────────┘
                                                                       ▼
                                                              Supabase RPCs + Realtime
```

### 6.2 Write path

1. UI calls `repository.addExpense(payload)`.
2. Repository writes the new `expenses` + `shares` rows to Room immediately, with a client-generated UUIDv7 ID and a stamped `created_at`.
3. Repository enqueues a `pending_mutations` row of `op_kind = 'ADD_EXPENSE'` with the same `target_id` and the full payload.
4. UI Flow re-emits with the optimistic row visible.
5. Sync worker picks up the mutation, calls `add_expense` RPC, receives the server's authoritative row.
6. Repository upserts the server row into Room (the `id` matches, so this is an update of the optimistic row).
7. Pending mutation row is deleted.

### 6.3 Read path

- All UI reads from Room (`Flow<...>` from DAO).
- Sync worker pulls server state on:
  - Cold start.
  - Foreground from background.
  - After every successful write (incremental delta).
  - Realtime events fire incremental updates directly.

### 6.4 Conflict resolution (LWW with version)

The server's row is authoritative. When pulling:

```
for each remote row R fetched:
    local = Room.find(R.id)
    if local == null:
        insert R
    else if R.updated_at > local.updated_at
         OR (R.updated_at == local.updated_at AND R.row_version > local.row_version):
        upsert R (overwrites local)
    else:
        // local is newer — keep local; sync worker will push the diff
        retain local; ensure a pending_mutation exists for it
```

On the write path, the `edit_expense` RPC takes `expected_row_version`. If the server's current `row_version` differs, RPC raises `STALE_ROW`. Client handles:

1. Re-fetch the server row.
2. Show a "This expense changed elsewhere; reload?" dialog.
3. On reload: discard the local mutation queue entry, refresh from server.
4. On retry (user opts to clobber): re-submit with the new `expected_row_version`.

This is the simplest model that meets correctness for v1. **Per-field merge is explicitly out of scope.**

### 6.5 Mutation queue properties

- Persisted across restarts (Room table).
- Retried with exponential backoff: 1s, 5s, 30s, 2m, 10m, 60m (cap).
- Network availability is observed via platform APIs (Android `ConnectivityManager`, iOS `NWPathMonitor`); on offline → online transition, all due mutations are eligible immediately.
- Idempotent: the server is responsible for accepting a second call with the same `id`. The client supplies the UUIDv7 it generated locally, and the RPC ON CONFLICT clauses upsert.

#### 6.5.1 Idempotency keys

All write RPCs take an optional `client_op_id` (UUIDv7) which the server stores in a small `idempotency` table; a second call with the same `client_op_id` returns the cached result. This protects against the "client thought the RPC failed, retried, but the server actually committed" case.

```sql
CREATE TABLE idempotency (
  client_op_id  uuid PRIMARY KEY,
  user_id       uuid NOT NULL REFERENCES users(id),
  rpc_name      text NOT NULL,
  response_json jsonb NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);
-- Cleaned up by a scheduled function after 7 days.
```

### 6.6 Sync triggers

| Event | Action |
|---|---|
| App cold start | Pull `members` for current user; for the home list, pull groups + counts; for the open group (if any) pull full delta. |
| Foreground from background | Same as cold start but only if last sync > 60s ago. |
| User pulls to refresh on a list | Force pull regardless of timestamp. |
| Realtime event received | Apply the change to Room directly; no RPC call. |
| `network → online` | Drain mutation queue. |
| FCM notification received (background) | If the app is killed/backgrounded, the push payload carries `group_id`; on next open, pull that group. |

### 6.7 Pull endpoints

Each pull is an RPC, not a raw table read, so RLS+pagination+`updated_at` filtering all happen server-side.

- `pull_group_delta(group_id, since: timestamptz) → { expenses[], shares[], settlements[], allocations[], comments[], history[], conflicts[], members[], categories[], subcategories[], receipts[], drafts[], server_now: timestamptz }` — `receipts[]` is metadata only (no file bytes); `drafts[]` is filtered to the caller's own drafts.
- `pull_home(since: timestamptz) → { groups[], own_members[], server_now: timestamptz }`

The client stores `server_now` in `sync_state.last_synced_at` for the next call.

### 6.8 Offline UX guarantees

- **Reads**: always work for any data the user has previously synced.
- **Writes**: optimistic; UI shows the row immediately. A small "Pending sync" badge appears on rows in the queue (visible only to the author; subtle).
- **Settlement deep links**: depend on the OS, not the network; work offline. The "Did the transfer go through?" confirm still writes to the local queue; server sync happens later.
- **Receipt uploads**: compression + thumbnail generation happen on-device immediately (`03 §15.1`); the compressed file and thumbnail stay on local storage (deduplicated by hash) and are queued. On reconnect, both objects upload first, then `confirm_receipt_upload` fires. Reading a receipt that is not in the Coil cache requires connectivity (lazy fetch, `03 §15.2`).
- **Drafts**: `save_draft` / `delete_draft` go through the queue; auto-save works fully offline and syncs to the user's other devices on reconnect.
- **Realtime**: disabled while offline; queue is the only path.

## 7. Storage uploads

Per `02 §8` and `03 §15`. The flow:

1. Client **compresses** the image (≥ 80% quality, long edge ≤ 2048 px) and **generates a thumbnail** (≤ 320 px). PDFs upload as-is; their thumbnail is page 1 rendered to JPEG.
2. Client calls `request_receipt_upload({ expense_id? | draft_id?, mime_type, byte_size, thumb_byte_size })` (exactly one parent id).
3. Server pre-flights:
   - Caller is an active member of the group (and, for a draft parent, the draft's owner).
   - The parent exists and has < 10 receipts.
   - `groups.storage_bytes_used + byte_size + thumb_byte_size <= 1 GB`.
   - If that sum crosses 500 MB for the first time, attach `warn: 'STORAGE_CAP_WARN'`.
4. Server returns presigned PUT URLs (valid 5 minutes) for `groups/{group_id}/receipts/{receipt_id}.{ext}` (full) and `…/{receipt_id}_thumb.jpg` (thumbnail).
5. Client PUTs both objects directly to Storage.
6. Client calls `confirm_receipt_upload(receipt_id)`.
7. Server stats **both** objects; if either is missing or size-mismatched, raises `VALIDATION`. Otherwise inserts the `receipts` row (which triggers the `storage_bytes_used` recompute).

The two-step ensures the `receipts` table doesn't carry phantom rows if an upload never happened.

## 8. Push notifications

### 8.1 Token registration

On every cold start (and on Firebase token refresh callbacks):

1. Get FCM token (Android) or APNs token (iOS, via Firebase iOS SDK or a thin native bridge).
2. Call `register_push_token`.

### 8.2 Permission

Both platforms require runtime permission for notifications. The app requests permission:

- On first cold start after onboarding completes.
- On first attempted "settled by you" event (so the recipient sees the confirmation push) — defensive prompt.

If denied, the app still works but in-app banners replace pushes.

### 8.3 Backend dispatch

All sends go through `dispatch_push` (Edge Function). Postgres triggers on:

- `expenses` INSERT → push `EXPENSE_ADDED` to participants.
- `expenses` UPDATE → push `EXPENSE_EDITED` (debounced 60s; if multiple updates within the window, only one push).
- `settlements` INSERT → push `SETTLEMENT_RECEIVED` to `to_user_id`.
- `comments` INSERT → push `COMMENT_ADDED` to participants.
- `members` INSERT (new active) → push `MEMBER_JOINED` to other active members.
- `conflicts` reminder scheduler → push `CONFLICT_REMINDER` to admin.

Each trigger calls `pg_net.http_post` to the Edge Function with the payload; `dispatch_push` consults prefs and archives before sending.

### 8.4 Deep link payload schema

```json
{
  "type": "EXPENSE_ADDED" | "EXPENSE_EDITED" | "SETTLEMENT_RECEIVED" |
          "COMMENT_ADDED" | "CONFLICT_REMINDER" | "MEMBER_JOINED",
  "group_id": "uuid",
  "expense_id": "uuid?",   // present for expense/settlement/comment
  "comment_id": "uuid?"    // present for COMMENT_ADDED
}
```

On tap, the client navigates per `05 §11`.

## 9. Versioning & forward-compat

- All RPC payloads include `_v: 1` (integer). Server reads with a tolerant decoder (unknown fields ignored). Major bumps are reserved for breaking shape changes; minor changes add nullable fields only.
- The client sends a `X-Client-Version` header on every RPC (auto-injected by `supabase-kt` interceptor). Server logs it; if a client below `min_supported_version` calls, server raises `CLIENT_TOO_OLD` and the app shows a force-update dialog.
- `min_supported_version` is stored in a server-side config row; checked once per session.

## 10. Observability hooks (forward references)

Detailed in `07-non-functional.md §4`. For sync specifically:

- Per-mutation: log `op_kind`, `target_id`, `attempt_count`, `success/failure`, `latency_ms`, `error_code`.
- Per-pull: log `since`, `rows_returned`, `latency_ms`.
- Per-Realtime event: count by channel name.
- Per-RPC: structured error rate.

---

**Read next:** [`05-ux-screens.md`](05-ux-screens.md).
