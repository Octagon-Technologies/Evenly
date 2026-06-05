# 02 — Data Model

> Concrete schema for Supabase Postgres and the mirroring Room KMP local schema. Includes column types, constraints, indices, RLS policies, and invariants the application MUST preserve.
>
> The local schema mirrors the server schema 1:1 with two additions: a `pending_mutations` queue table and a `sync_state` row-per-table table. Both are described in `04-api-and-sync.md §6`.

---

## 1. Conventions

- **Naming.** snake_case. Table names plural; column names singular.
- **IDs.** `uuid` columns populated from UUIDv7 generated client-side (see `06 §5`). PRIMARY KEY on `id`.
- **Timestamps.** `timestamptz` (UTC) with `default now()` where appropriate. Logical "dates" use `date`.
- **Money.** `bigint` subunit value (`amount_subunits`) plus `text` currency code (`currency`) and a derived `amount_decimal` view column when needed for display.
- **Soft delete.** `deleted_at timestamptz` nullable; default queries include `WHERE deleted_at IS NULL`.
- **Versioning.** Every mutable row has `updated_at timestamptz default now()` plus a `row_version bigint default 1` that increments on each update (via trigger). LWW conflict resolution compares `updated_at` then `row_version` as tiebreaker.
- **Enums.** Implemented as `text CHECK (... IN (...))` rather than Postgres `enum` types, so adding values is a one-line migration.

## 2. ID generation

- Clients generate UUIDv7 for all new rows. UUIDv7 sorts roughly by creation time, which helps:
  - Local index locality.
  - Sync ordering on conflict.
  - Pagination using `id` as a tiebreaker for stable cursors.
- Server NEVER overrides a client-supplied `id` (clients must trust their own IDs to make offline-first writes idempotent).
- Implementing agents MUST generate UUIDv7 via the Kotlin standard library (`kotlin.uuid.Uuid`) — **no third-party UUID library** (`kulid` produces ULIDs, not valid `uuid` values). On Kotlin ≥ 2.4 use `Uuid.generateV7()`; on the pinned Kotlin 2.3.21 baseline use the vetted ~30-line RFC 9562 generator in `core/UuidV7.kt`. See `06 §5.8` (and `06 §2.1` for the rationale).

## 3. Tables

### 3.1 currencies (static reference)

Seeded once at deploy. Read-only at runtime.

```sql
CREATE TABLE currencies (
  code        text PRIMARY KEY,           -- ISO 4217, e.g. 'USD'
  name        text NOT NULL,
  symbol      text NOT NULL,              -- '$', '€', '¥'
  minor_unit  smallint NOT NULL CHECK (minor_unit BETWEEN 0 AND 4),  -- 2 for USD, 0 for JPY
  display_order smallint NOT NULL DEFAULT 1000
);
```

Seed at least the ISO 4217 currencies Frankfurter supports (~30) plus `JPY`, `KRW`, `VND` for zero-decimal handling. Display order: `USD` first, then by `name` ascending.

### 3.2 users

A single table holding both real Users and Placeholder participants.

```sql
CREATE TABLE users (
  id               uuid PRIMARY KEY,
  is_placeholder   boolean NOT NULL DEFAULT false,
  display_name     text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 80),
  email            citext UNIQUE,         -- NULL iff is_placeholder
  avatar_url       text,
  base_currency    text REFERENCES currencies(code) DEFAULT 'USD',
  placeholder_group_id uuid REFERENCES groups(id) ON DELETE CASCADE,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  row_version      bigint NOT NULL DEFAULT 1,

  CONSTRAINT users_real_has_email
    CHECK (is_placeholder OR email IS NOT NULL),
  CONSTRAINT users_placeholder_has_group
    CHECK ((NOT is_placeholder) OR placeholder_group_id IS NOT NULL),
  CONSTRAINT users_placeholder_no_email
    CHECK ((NOT is_placeholder) OR email IS NULL)
);

CREATE UNIQUE INDEX users_placeholder_name_per_group
  ON users (placeholder_group_id, lower(display_name))
  WHERE is_placeholder;
```

**Invariant.** `is_placeholder` rows never exist without a `placeholder_group_id`. Real-user rows have NULL `placeholder_group_id`. The Supabase `auth.users` row's `id` is reused as `users.id` for real users (so JWT `sub` claim equals `users.id`).

**Circular FK ordering.** `users.placeholder_group_id` references `groups(id)`, and `groups` (`§3.4`) references `users(id)` (`admin_user_id`, `created_by`). Because the two tables reference each other, implementing agents MUST create both tables first **without** the `placeholder_group_id` FK, then add it via `ALTER TABLE users ADD CONSTRAINT ... FOREIGN KEY (placeholder_group_id) REFERENCES groups(id) ON DELETE CASCADE` after `groups` exists.

**Reserved system user.** A single sentinel row with `id = '00000000-0000-0000-0000-000000000000'` is seeded at deploy to author system-generated rows (auto-refunds — see `03 §8.7`). To satisfy `users_real_has_email`, it is seeded with `is_placeholder = false` and a reserved, non-routable email `system@sharecost.invalid`. This row MUST NEVER appear in any member list, picker, balance, or push-recipient set; all member-facing queries exclude `id = '00000000-0000-0000-0000-000000000000'`.

### 3.3 payment_app_handles

```sql
CREATE TABLE payment_app_handles (
  id            uuid PRIMARY KEY,
  user_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  app           text NOT NULL CHECK (app IN ('VENMO','ZELLE','CASH_APP','PAYPAL')),
  handle        text NOT NULL CHECK (length(handle) BETWEEN 1 AND 120),
  is_primary    boolean NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  row_version   bigint NOT NULL DEFAULT 1,

  UNIQUE (user_id, app)                    -- one handle per app per user
);

CREATE UNIQUE INDEX payment_app_handles_one_primary_per_user
  ON payment_app_handles (user_id)
  WHERE is_primary;
```

**Invariant.** Setting `is_primary = true` on one row MUST flip any other primary row for the same user to `false` (handled in the `set_primary_payment_handle` RPC; UI MUST NOT permit two primaries).

### 3.4 groups

```sql
CREATE TABLE groups (
  id            uuid PRIMARY KEY,
  name          text NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
  emoji         text NOT NULL DEFAULT '💸' CHECK (length(emoji) BETWEEN 1 AND 8),
  base_currency text NOT NULL REFERENCES currencies(code) DEFAULT 'USD',
  admin_user_id uuid REFERENCES users(id),    -- NULL when group is abandoned
  invite_token  text NOT NULL UNIQUE,         -- URL-safe random, regenerated on rotation
  invite_token_rotated_at timestamptz,
  reminder_cadence text NOT NULL DEFAULT 'WEEKLY'
    CHECK (reminder_cadence IN ('DAILY','EVERY_THREE_DAYS','WEEKLY')),
  last_conflict_reminder_at timestamptz,      -- last time notify_admin_of_conflicts pinged the admin (dedup)
  storage_bytes_used bigint NOT NULL DEFAULT 0,
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    uuid NOT NULL REFERENCES users(id),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  row_version   bigint NOT NULL DEFAULT 1,
  deleted_at    timestamptz
);
```

**Invariants.**
- `admin_user_id` MUST be an active member of the group, or NULL (abandoned).
- `storage_bytes_used` is maintained by a trigger on `receipts` (sum of `byte_size + thumb_byte_size`). It is the source of truth for the soft/hard caps (D-09).
- `base_currency` defaults to `USD` at the column level, but the `create_group` RPC sets it to the **creator's `users.base_currency`** unless the caller passes an explicit value (D-27, OQ-04). The column DEFAULT is only a backstop.
- `last_conflict_reminder_at` is written only by the `notify_admin_of_conflicts` Edge Function (`03 §8.5`, `04 §3.2`).

### 3.5 members

```sql
CREATE TABLE members (
  id            uuid PRIMARY KEY,
  group_id      uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status        text NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','LEFT')),
  is_admin      boolean NOT NULL DEFAULT false,
  joined_at     timestamptz NOT NULL DEFAULT now(),
  left_at       timestamptz,
  archived_at   timestamptz,                 -- per-(user,group) archive flag
  placeholder_claim_completed_at timestamptz, -- non-null after first reconcile run
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  row_version   bigint NOT NULL DEFAULT 1,

  UNIQUE (group_id, user_id)
);

CREATE INDEX members_by_group ON members (group_id) WHERE status = 'ACTIVE';
CREATE INDEX members_by_user_active ON members (user_id) WHERE status = 'ACTIVE';
```

**Invariants.**
- At most one `is_admin = true` per `group_id`.
- A `placeholder` user's `members` row is unique per group (enforced by the `(group_id, user_id)` unique).
- `status = 'LEFT'` implies `left_at IS NOT NULL`.

### 3.6 categories & subcategories

Per-group, seeded on group creation; editable by any member.

```sql
CREATE TABLE categories (
  id            uuid PRIMARY KEY,
  group_id      uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  name          text NOT NULL CHECK (length(name) BETWEEN 1 AND 40),
  icon          text NOT NULL,              -- icon key from a fixed icon set
  display_order smallint NOT NULL DEFAULT 1000,
  is_system     boolean NOT NULL DEFAULT false,   -- true for seeded; user-deletable still
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  row_version   bigint NOT NULL DEFAULT 1,
  deleted_at    timestamptz,

  UNIQUE (group_id, lower(name))
);

CREATE TABLE subcategories (
  id            uuid PRIMARY KEY,
  category_id   uuid NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
  name          text NOT NULL CHECK (length(name) BETWEEN 1 AND 40),
  icon          text,
  display_order smallint NOT NULL DEFAULT 1000,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  row_version   bigint NOT NULL DEFAULT 1,
  deleted_at    timestamptz,

  UNIQUE (category_id, lower(name))
);
```

**Seed on group create (idempotent server-side function `seed_default_categories(group_id)`):**

- 🍽 Food → Groceries, Restaurant, Snacks, Coffee
- 🚗 Transport → Fuel, Taxi/Rideshare, Public, Parking
- 🏠 Housing → Rent, Utilities, Internet, Supplies
- 🎟 Entertainment → Tickets, Streaming, Activities
- 🛍 Shopping → Clothing, Gifts, Other
- 🏥 Health → Pharmacy, Doctor
- ✈️ Travel → Flights, Lodging, Activities
- 💼 Work → Meals, Travel, Other
- 🪙 Other

`is_system = true` is purely informational and does NOT prevent deletion; on delete, all expenses tagged with that category are reassigned to the "Other" category for the same group (created if missing) and to a NULL subcategory.

### 3.7 expenses

```sql
CREATE TABLE expenses (
  id                 uuid PRIMARY KEY,
  group_id           uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  kind               text NOT NULL CHECK (kind IN ('EXPENSE','REFUND')),
  title              text NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  notes              text,
  amount_subunits       bigint NOT NULL CHECK (amount_subunits > 0),
  currency           text NOT NULL REFERENCES currencies(code),
  expense_date       date NOT NULL,                    -- local calendar date

  payer_user_id      uuid REFERENCES users(id),        -- NULL iff payer_outside_name is set
  payer_outside_name text,                              -- non-member payer

  split_mode         text NOT NULL CHECK (split_mode IN ('EVEN','BY_SHARE','BY_PERCENTAGE','BY_EXACT')),
  has_tax_row        boolean NOT NULL DEFAULT false,   -- itemized restaurant split toggle (covers tax AND tip)
  tax_subunits          bigint NOT NULL DEFAULT 0 CHECK (tax_subunits >= 0),
  tip_subunits          bigint NOT NULL DEFAULT 0 CHECK (tip_subunits >= 0),
  tip_split_mode     text NOT NULL DEFAULT 'PROPORTIONAL'
                       CHECK (tip_split_mode IN ('PROPORTIONAL','EVEN')),

  category_id        uuid REFERENCES categories(id),
  subcategory_id     uuid REFERENCES subcategories(id),

  refund_of_expense_id uuid REFERENCES expenses(id),   -- forward-link for refund chain
  is_auto_refund     boolean NOT NULL DEFAULT false,

  created_by         uuid NOT NULL REFERENCES users(id),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  row_version        bigint NOT NULL DEFAULT 1,
  deleted_at         timestamptz,

  CONSTRAINT expenses_payer_xor_outside
    CHECK ((payer_user_id IS NOT NULL) <> (payer_outside_name IS NOT NULL)),
  CONSTRAINT expenses_auto_refund_implies_refund
    CHECK ((NOT is_auto_refund) OR kind = 'REFUND'),
  CONSTRAINT expenses_refund_chain_depth
    CHECK (refund_of_expense_id IS NULL OR kind = 'REFUND'),
  -- Tax and tip are only meaningful in the itemized (has_tax_row) flow.
  CONSTRAINT expenses_tax_tip_requires_itemized
    CHECK (has_tax_row OR (tax_subunits = 0 AND tip_subunits = 0))
);

CREATE INDEX expenses_by_group_date ON expenses (group_id, expense_date DESC) WHERE deleted_at IS NULL;
CREATE INDEX expenses_by_payer ON expenses (payer_user_id) WHERE deleted_at IS NULL;
CREATE INDEX expenses_refund_of ON expenses (refund_of_expense_id) WHERE refund_of_expense_id IS NOT NULL;
```

**Invariants.**
- Exactly one of `payer_user_id` or `payer_outside_name` is set (enforced).
- A refund of a refund is invalid; enforced at validation in the RPC (the CHECK above only blocks non-REFUND rows from carrying a `refund_of_expense_id`, not the recursive case; the RPC adds the second check).
- `amount_subunits` is always positive. A refund's "negative" semantics live in `kind` and the inversion of participants, not in a sign on the amount.
- When `has_tax_row = true`, `amount_subunits` equals `SUM(participant subtotals) + tax_subunits + tip_subunits`, and each `shares.share_owed_subunits` equals that participant's `subtotal + tax_share + tip_share` (see `03 §1.4`). Therefore `SUM(shares.share_owed_subunits) == amount_subunits` still holds with tax and tip folded in.

### 3.8 shares

```sql
CREATE TABLE shares (
  id                uuid PRIMARY KEY,
  expense_id        uuid NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
  user_id           uuid NOT NULL REFERENCES users(id),
  share_owed_subunits  bigint NOT NULL CHECK (share_owed_subunits >= 0),
  remaining_subunits   bigint NOT NULL CHECK (remaining_subunits >= 0),
  -- Inputs preserved so the expense can be re-rendered in its split editor:
  share_units       integer,                 -- for BY_SHARE
  share_percentage  numeric(7,4),            -- for BY_PERCENTAGE, e.g. 33.3333
  share_exact_subunits bigint,                  -- for BY_EXACT
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  row_version       bigint NOT NULL DEFAULT 1,

  UNIQUE (expense_id, user_id),
  CONSTRAINT shares_remaining_not_exceeds_owed
    CHECK (remaining_subunits <= share_owed_subunits)
);

CREATE INDEX shares_by_user_active ON shares (user_id) WHERE remaining_subunits > 0;
CREATE INDEX shares_by_expense ON shares (expense_id);
```

**Invariants.**
- `SUM(share_owed_subunits) WHERE expense_id = X` equals `expenses.amount_subunits` for `X` (cent-perfect — see `03 §1`).
- `remaining_subunits <= share_owed_subunits` always (CHECK).
- For an expense's payer: their share is recorded like any other participant if they are a participant; if not, no share row exists for them.

### 3.9 settlements

A Settlement is a payment event. It links to one or more shares it pays down. A single payment can pay multiple expenses (multi-expense settlement); a single share can be paid down by many settlements (partial pay).

```sql
CREATE TABLE settlements (
  id                  uuid PRIMARY KEY,
  group_id            uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  from_user_id        uuid NOT NULL REFERENCES users(id),    -- payer
  to_user_id          uuid NOT NULL REFERENCES users(id),    -- recipient
  payment_currency    text NOT NULL REFERENCES currencies(code),
  payment_amount_subunits bigint NOT NULL CHECK (payment_amount_subunits > 0),
  payment_app         text CHECK (payment_app IN ('VENMO','ZELLE','CASH_APP','PAYPAL','MANUAL')),
  deep_link_attempted boolean NOT NULL DEFAULT false,
  deep_link_succeeded boolean,
  settled_at          timestamptz NOT NULL DEFAULT now(),     -- when user confirmed
  notes               text,

  created_by          uuid NOT NULL REFERENCES users(id),
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  row_version         bigint NOT NULL DEFAULT 1,
  deleted_at          timestamptz,

  CONSTRAINT settlements_distinct_parties CHECK (from_user_id <> to_user_id)
);

CREATE INDEX settlements_by_group ON settlements (group_id, settled_at DESC) WHERE deleted_at IS NULL;
```

```sql
CREATE TABLE settlement_allocations (
  id                  uuid PRIMARY KEY,
  settlement_id       uuid NOT NULL REFERENCES settlements(id) ON DELETE CASCADE,
  share_id            uuid NOT NULL REFERENCES shares(id),
  applied_amount_subunits bigint NOT NULL CHECK (applied_amount_subunits > 0),
  applied_currency    text NOT NULL REFERENCES currencies(code),   -- equals the share's currency
  fx_rate_used        numeric(20,10),                             -- NULL if same currency
  fx_rate_date        date,                                       -- NULL if same currency
  created_at          timestamptz NOT NULL DEFAULT now(),

  UNIQUE (settlement_id, share_id)
);

CREATE INDEX settlement_allocations_by_share ON settlement_allocations (share_id);
```

**Invariants.**
- `SUM(applied_amount_subunits)` in the **payment currency** (after converting each allocation back via `fx_rate_used`) equals `settlements.payment_amount_subunits` within a rounding tolerance of 1 subunit per allocation (the leftover is absorbed by the last allocation; algorithm in `03 §4`).
- Each allocation's `applied_amount_subunits` is denominated in the **share's** currency (so applying it directly reduces `shares.remaining_subunits`).
- A delete of a settlement (soft) does NOT auto-revert allocations on its shares; instead a separate "void settlement" RPC reverses each allocation in a transaction. Plain delete is reserved for cases the UI rejects.

### 3.10 receipts

```sql
CREATE TABLE receipts (
  id              uuid PRIMARY KEY,
  group_id        uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,  -- denormalized for RLS + storage accounting
  expense_id      uuid REFERENCES expenses(id) ON DELETE CASCADE,         -- NULL iff attached to a draft
  draft_id        uuid REFERENCES drafts(id) ON DELETE CASCADE,           -- NULL iff attached to an expense
  storage_path    text NOT NULL,          -- compressed full image: 'groups/{group_id}/receipts/{id}.{ext}'
  thumb_storage_path text NOT NULL,       -- client-generated thumbnail: 'groups/{group_id}/receipts/{id}_thumb.jpg'
  mime_type       text NOT NULL CHECK (mime_type IN ('image/jpeg','image/png','image/heic','application/pdf')),
  byte_size       bigint NOT NULL CHECK (byte_size > 0 AND byte_size <= 30 * 1024 * 1024),  -- compressed full size
  thumb_byte_size bigint NOT NULL DEFAULT 0 CHECK (thumb_byte_size >= 0),
  width_px        integer,
  height_px       integer,
  uploaded_by     uuid NOT NULL REFERENCES users(id),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  row_version     bigint NOT NULL DEFAULT 1,
  deleted_at      timestamptz,

  CONSTRAINT receipts_expense_xor_draft
    CHECK ((expense_id IS NOT NULL) <> (draft_id IS NOT NULL))
);

CREATE INDEX receipts_by_expense ON receipts (expense_id) WHERE deleted_at IS NULL AND expense_id IS NOT NULL;
CREATE INDEX receipts_by_draft   ON receipts (draft_id)   WHERE deleted_at IS NULL AND draft_id IS NOT NULL;
```

**Compression & thumbnails (D-22, OQ-11).** Receipts are NOT stored at original resolution. Before upload the client:

1. Compresses the image to JPEG/HEIC at quality ≥ 0.80, capping the long edge at 2048 px (PDFs are uploaded as-is). `byte_size` records the **compressed** size; the 30 MB CHECK applies to the compressed file.
2. Generates a small thumbnail (long edge ≤ 320 px, JPEG quality ~0.7) for list/scroller display. For PDFs the thumbnail is page 1 rendered to JPEG via the platform helper (`06 §5.9`). `thumb_byte_size` records its size.

Both objects upload to Storage (`§8`); both count toward `groups.storage_bytes_used`.

**Lazy fetch.** Receipts are never bulk-synced to the device. The thumbnail is fetched when a receipt scroller renders; the full compressed image is fetched only when the user opens it. Both are cached in Coil's disk cache with a **14-day TTL** (`07 §10.1`), after which they are evicted and re-fetched on demand.

**Author-only deletion (D-26, OQ-06).** Only `uploaded_by` may delete a receipt; enforced by the `delete_receipt` RPC (`04 §2.3`).

Per-expense and per-draft max 10 receipts enforced by trigger or by the upload RPC (a CHECK can't count peers). Per-attachment max 30 MB (compressed) enforced by CHECK.

### 3.11 comments

```sql
CREATE TABLE comments (
  id            uuid PRIMARY KEY,
  expense_id    uuid NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
  author_user_id uuid NOT NULL REFERENCES users(id),
  body          text NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
  is_resolved   boolean NOT NULL DEFAULT false,
  resolved_at   timestamptz,
  resolved_by   uuid REFERENCES users(id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  row_version   bigint NOT NULL DEFAULT 1,
  deleted_at    timestamptz
);

CREATE INDEX comments_by_expense ON comments (expense_id, created_at) WHERE deleted_at IS NULL;
```

```sql
CREATE TABLE comment_read_state (
  expense_id    uuid NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
  user_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  last_read_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (expense_id, user_id)
);
```

Unread indicator: a participant has unreads iff the latest `comments.created_at` for the expense > their `comment_read_state.last_read_at` (or no read state exists).

### 3.12 history_events

Append-only. No update/delete on this table is ever permitted (enforced by RLS denying both for everyone, even service role only allows insert + select).

```sql
CREATE TABLE history_events (
  id            uuid PRIMARY KEY,
  group_id      uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  expense_id    uuid NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
  event_type    text NOT NULL CHECK (event_type IN (
    'CREATED','EDITED','PARTICIPANT_ADDED','PARTICIPANT_REMOVED',
    'SPLIT_CHANGED','SETTLED','SETTLEMENT_VOIDED','REFUND_LINKED',
    'RETRO_RESPLIT','CONFLICT_RESOLVED','AUTO_REFUND_GENERATED',
    'COMMENT_ADDED','COMMENT_RESOLVED','PARTICIPANT_MERGED','DELETED'
  )),
  actor_user_id uuid REFERENCES users(id),    -- NULL for system events (e.g., AUTO_REFUND_GENERATED)
  payload       jsonb NOT NULL DEFAULT '{}',
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX history_by_expense ON history_events (expense_id, created_at);
CREATE INDEX history_by_group_recent ON history_events (group_id, created_at DESC);
```

Standard payload shapes (each `event_type`):

| event_type | payload keys |
|---|---|
| `CREATED` | `{ amount_subunits, currency, payer_user_id?, payer_outside_name?, split_mode, participant_ids }` |
| `EDITED` | `{ changes: { field: [oldValue, newValue], ... } }` |
| `PARTICIPANT_ADDED` / `_REMOVED` | `{ user_id, display_name_at_time }` |
| `SPLIT_CHANGED` | `{ from_mode, to_mode, inputs }` |
| `SETTLED` | `{ settlement_id, from_user_id, to_user_id, applied_amount_subunits, applied_currency }` |
| `SETTLEMENT_VOIDED` | `{ settlement_id, reason }` |
| `REFUND_LINKED` | `{ refund_expense_id, amount_subunits, currency }` |
| `RETRO_RESPLIT` | `{ added_user_id, decision: 'EQUAL_AUTO' \| 'MANUAL', new_share_subunits? }` |
| `CONFLICT_RESOLVED` | `{ added_user_id, decision: 'INCLUDE' \| 'DISMISS', new_share_subunits? }` |
| `AUTO_REFUND_GENERATED` | `{ refund_expense_id, to_user_id, amount_subunits, currency, reason: 'RETRO_RESPLIT_SETTLED_OVERPAY' }` |
| `COMMENT_ADDED` | `{ comment_id }` |
| `COMMENT_RESOLVED` | `{ comment_id }` |
| `PARTICIPANT_MERGED` | `{ from: placeholder_user_id, fromName, to: real_user_id }` |
| `DELETED` | `{}` |

### 3.13 conflicts

```sql
CREATE TABLE conflicts (
  id                uuid PRIMARY KEY,
  group_id          uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  expense_id        uuid NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
  added_user_id     uuid NOT NULL REFERENCES users(id),
  triggered_by_user_id uuid NOT NULL REFERENCES users(id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  resolved_at       timestamptz,
  resolved_by_user_id uuid REFERENCES users(id),
  resolution        text CHECK (resolution IN ('INCLUDE','DISMISS')),

  UNIQUE (expense_id, added_user_id)
);

CREATE INDEX conflicts_by_group_open ON conflicts (group_id) WHERE resolved_at IS NULL;
```

### 3.14 fx_rates

Global; rates are stored as `(USD, quote)`. Cross-currency conversion goes via USD (see `03 §6`).

```sql
CREATE TABLE fx_rates (
  rate_date     date NOT NULL,
  quote_currency text NOT NULL REFERENCES currencies(code),
  rate_per_usd  numeric(20,10) NOT NULL CHECK (rate_per_usd > 0),
  source        text NOT NULL DEFAULT 'FRANKFURTER',
  fetched_at    timestamptz NOT NULL DEFAULT now(),

  PRIMARY KEY (rate_date, quote_currency)
);

CREATE INDEX fx_rates_recent ON fx_rates (quote_currency, rate_date DESC);
```

Local Room mirror is identical. A separate Room-only table `fx_baked` ships with the app binary as the build-time snapshot; the lookup chain is Room `fx_rates` → Room `fx_baked` → return `null` (and the UI shows an informational state).

### 3.15 device_push_tokens

```sql
CREATE TABLE device_push_tokens (
  id              uuid PRIMARY KEY,
  user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform        text NOT NULL CHECK (platform IN ('ANDROID_FCM','IOS_APNS')),
  token           text NOT NULL,                     -- FCM token or APNs token
  app_install_id  text NOT NULL,                     -- our own install UUID, stable per install
  locale          text,
  last_seen_at    timestamptz NOT NULL DEFAULT now(),
  created_at      timestamptz NOT NULL DEFAULT now(),

  UNIQUE (app_install_id, platform)
);
```

A user can have many tokens (one per device). On token refresh, the new token replaces the row matching `(app_install_id, platform)`.

### 3.16 notification_preferences

```sql
CREATE TABLE notification_preferences (
  user_id            uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  expense_added      boolean NOT NULL DEFAULT true,
  expense_edited     boolean NOT NULL DEFAULT true,
  settlement_received boolean NOT NULL DEFAULT true,
  comment_added      boolean NOT NULL DEFAULT true,
  conflict_reminder  boolean NOT NULL DEFAULT true,   -- only relevant to admins
  member_joined      boolean NOT NULL DEFAULT true,
  updated_at         timestamptz NOT NULL DEFAULT now(),
  row_version        bigint NOT NULL DEFAULT 1
);
```

### 3.17 drafts (server-synced expense drafts, D-24, OQ-13)

A **draft** is a half-filled Add-Expense form, owned by exactly one user, synced across that user's devices. It is private (never visible to other group members), supports the "attach a receipt now, split it later" (receipt-first) flow, and is converted into a real expense on submit.

```sql
CREATE TABLE drafts (
  id            uuid PRIMARY KEY,                       -- client-generated UUIDv7; becomes the expense id on convert
  group_id      uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  payload_json  jsonb NOT NULL DEFAULT '{}',            -- partial AddExpense form state; all fields optional/nullable
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  row_version   bigint NOT NULL DEFAULT 1,
  deleted_at    timestamptz
);

CREATE INDEX drafts_by_owner_group ON drafts (owner_user_id, group_id) WHERE deleted_at IS NULL;
```

**Notes.**
- `drafts` is referenced by `receipts.draft_id` (`§3.10`). Because `receipts` references both `expenses` and `drafts`, create `drafts` **before** the `receipts` FKs, or add the `receipts.draft_id` FK via `ALTER` after both exist.
- `payload_json` holds whatever the form currently has (title, amount, currency, participants, split inputs, category, notes, tax/tip). It is intentionally schemaless so a draft never fails validation — validation happens only at convert time (`03 §3.6`).
- The draft `id` is reused as the new expense `id` on convert, so any receipts pointing at the draft re-point by id and the optimistic-sync identity is preserved.

## 4. Triggers

Implementing agents MUST create these triggers (or equivalent server-side logic in the RPCs that mutate these rows).

1. **`updated_at` + `row_version`**: on every UPDATE of a row, set `updated_at = now()` and `row_version = row_version + 1`. Apply to all tables with `row_version`.
2. **`receipts → groups.storage_bytes_used`**: on INSERT, UPDATE, DELETE of `receipts`, recompute the running `storage_bytes_used` for the affected group as `SUM(byte_size + thumb_byte_size) WHERE group_id = G AND deleted_at IS NULL`.
3. **`receipts` per-parent count**: BEFORE INSERT on `receipts`, raise `RECEIPT_COUNT_EXCEEDED` if the parent (its `expense_id` **or** `draft_id`, whichever is set) already has ≥ 10 non-deleted receipts.
4. **Single admin**: BEFORE INSERT/UPDATE on `members`, if `is_admin = true`, raise if another active member in the same group has `is_admin = true`.
5. **History append-only**: REVOKE UPDATE, DELETE on `history_events` from all roles including `authenticated`. Inserts only.
6. **`expenses.status` maintenance**: maintain the denormalized `status` column (`§6`) from `shares.remaining_subunits` sums and `deleted_at`, via triggers on `expenses` (INSERT/soft-delete) and `shares` (INSERT/UPDATE/DELETE). It is a plain column, NOT a `GENERATED` column.

## 5. Row-level security (RLS)

All tables have RLS enabled. The fundamental predicate is "the requesting user is a `members` row in the relevant group with `status = 'ACTIVE'`." The exceptions are noted per table.

Helper view (security-invoker = false) used by policies:

```sql
CREATE OR REPLACE FUNCTION is_active_member(g uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER AS $$
  SELECT EXISTS (
    SELECT 1 FROM members
    WHERE group_id = g AND user_id = auth.uid() AND status = 'ACTIVE'
  );
$$;
```

### 5.1 users

- **SELECT**: a user can read their own row. A user can read any user row referenced by a group they are an active member of (resolved via a `members` join in the policy).
- **UPDATE**: self only (`auth.uid() = id`). Placeholder rows are not updatable via direct table access — only via the `claim_placeholders` RPC (which uses SECURITY DEFINER).
- **INSERT**: only via the signup trigger (`auth.users → users` mirror) or by the `add_placeholder_participant` RPC (SECURITY DEFINER).
- **DELETE**: forbidden. Account deletion uses a dedicated RPC.

### 5.2 groups, members, expenses, shares, settlements, settlement_allocations, receipts, comments, comment_read_state, conflicts, history_events, categories, subcategories

- **SELECT**: `is_active_member(group_id)` — for `users`, resolved through any group the requesting user shares with the row.
- **INSERT**: `is_active_member(group_id)` plus row-specific predicate (e.g., `expenses.created_by = auth.uid()`).
- **UPDATE**: `is_active_member(group_id)`. Additionally:
  - `shares.remaining_subunits`: only modifiable via the `apply_settlement` and `void_settlement` RPCs (RLS denies direct updates to this column for non-service roles).
  - `groups.invite_token`: only via `rotate_invite_token` RPC; direct update denied.
  - `history_events`: insert-only as noted.
- **DELETE**: `is_active_member(group_id)` for soft-delete columns being set; physical DELETE is denied except for unreferenced rows during user-initiated account purge.

**Receipts SELECT exception.** A receipt attached to an **expense** is visible to active members of its group. A receipt attached to a **draft** is private: visible only to the draft owner. The SELECT policy is therefore:

```sql
USING (
  is_active_member(group_id)
  AND (
    expense_id IS NOT NULL
    OR EXISTS (SELECT 1 FROM drafts d WHERE d.id = receipts.draft_id AND d.owner_user_id = auth.uid())
  )
)
```

Receipt **soft-delete** is further restricted to the uploader by the `delete_receipt` RPC (`04 §2.3`, D-26): direct table UPDATE setting `deleted_at` is denied; the RPC checks `uploaded_by = auth.uid()`.

### 5.2a drafts

- **SELECT / INSERT / UPDATE / DELETE**: owner only (`owner_user_id = auth.uid()`) AND `is_active_member(group_id)`. Drafts are never visible to other members. Soft-delete via `deleted_at`.

### 5.3 fx_rates

- **SELECT**: any authenticated user.
- **INSERT/UPDATE/DELETE**: service role only (Edge Function `refresh_fx_rates`).

### 5.4 payment_app_handles, notification_preferences, device_push_tokens

- **SELECT**: `payment_app_handles` are readable by any user who shares an active group with the handle's owner (so members can see each other's preferred apps when settling). `notification_preferences` and `device_push_tokens` are readable only by the owning user.
- **INSERT/UPDATE/DELETE**: owner only (`auth.uid() = user_id`).

### 5.5 currencies

- **SELECT**: any authenticated user.
- All other operations: deny.

## 6. Indices summary

In addition to the per-table indices above, create:

Indexing "active expenses" cheaply requires a denormalized `expenses.status` column — Postgres cannot index a correlated `EXISTS`, and a `GENERATED` column cannot read other tables (`shares`), so it MUST be a **plain column maintained by trigger**, not `GENERATED`:

```sql
ALTER TABLE expenses
  ADD COLUMN status text NOT NULL DEFAULT 'ACTIVE'
  CHECK (status IN ('ACTIVE','SETTLED','DELETED'));

-- Maintained by the trigger in §4 item 6:
--   'DELETED'  when deleted_at IS NOT NULL
--   'SETTLED'  when not deleted AND SUM(shares.remaining_subunits) = 0
--   'ACTIVE'   otherwise
CREATE INDEX expenses_active_by_group_date
  ON expenses (group_id, expense_date DESC, id DESC)
  WHERE status = 'ACTIVE';
```

Implementing agents MUST NOT use a `GENERATED ALWAYS` column for `status`; the value depends on `shares`, which a generated expression cannot reference. The trigger in `§4` item 6 is the single source of truth for this column.

## 7. Local Room schema

The Room (KMP) schema mirrors the Postgres schema with the following adjustments:

1. All `timestamptz` fields are stored as `INTEGER` (epoch milliseconds, UTC).
2. All `uuid` fields are stored as `TEXT`.
3. All `jsonb` fields are stored as `TEXT` (serialized JSON).
4. `citext` becomes `TEXT COLLATE NOCASE`.
5. Computed columns (e.g., `expenses.status`) are recomputed in app code on row insert/update rather than via Room triggers.
6. RLS does not apply; the local cache only ever contains rows the user is entitled to see (filtered server-side at sync time).
7. `receipts` rows (metadata only: id, paths, sizes, mime, dimensions) are mirrored so the receipt scroller can render counts and thumbnails; the file bytes are NOT stored in Room — they live in Coil's disk cache with a 14-day TTL (`07 §10.1`) and are fetched on demand via `mint_receipt_url`.
8. `drafts` rows are mirrored and participate in the pending-mutation queue like any other write (`04 §6`), so a draft started offline on one device syncs to the user's other devices on reconnect.

Room-only tables:

```sql
-- Pending local mutations awaiting server confirmation.
CREATE TABLE pending_mutations (
  id            TEXT PRIMARY KEY,            -- UUIDv7
  op_kind       TEXT NOT NULL,                -- e.g. 'ADD_EXPENSE','EDIT_EXPENSE','APPLY_SETTLEMENT', ...
  target_id     TEXT NOT NULL,                -- the row ID being mutated
  group_id      TEXT,
  payload_json  TEXT NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error    TEXT,
  created_at    INTEGER NOT NULL,
  next_attempt_at INTEGER NOT NULL
);

CREATE INDEX pending_mutations_next ON pending_mutations (next_attempt_at);

-- Last-known server sync cursor per table.
CREATE TABLE sync_state (
  table_name    TEXT PRIMARY KEY,
  last_synced_at INTEGER NOT NULL,
  last_synced_row_version INTEGER NOT NULL DEFAULT 0
);

-- Build-time FX snapshot.
CREATE TABLE fx_baked (
  quote_currency TEXT NOT NULL,
  rate_per_usd  REAL NOT NULL,
  snapshot_date TEXT NOT NULL,          -- ISO-8601 date
  PRIMARY KEY (quote_currency)
);
```

## 8. Storage layout (Supabase Storage)

- Bucket: **`receipts`** (private).
- Paths (decoupled from `expense_id`/`draft_id` so a receipt-first draft's files do not need to move when the draft converts to an expense):
  - Full compressed image: `groups/{group_id}/receipts/{receipt_id}.{ext}`.
  - Thumbnail: `groups/{group_id}/receipts/{receipt_id}_thumb.jpg`.
- Access: signed URLs only, 1-hour TTL, minted on demand by the `mint_receipt_url` RPC (SECURITY DEFINER, predicate: same as the receipts SELECT policy in `§5` — active member for expense receipts, owner for draft receipts). `mint_receipt_url` returns signed URLs for **both** the thumbnail and the full image.
- Bucket policy: deny anonymous; allow service role.
- Files are uploaded **compressed** (≥ 80% quality) with a separate client-generated thumbnail (`§3.10`); both objects count toward the cap.
- Per-group soft cap (500 MB) and hard cap (1 GB): enforced in the `request_receipt_upload` RPC, which atomically (a) checks `groups.storage_bytes_used + byte_size + thumb_byte_size <= 1 GB`, (b) returns presigned upload URLs for the full image and the thumbnail, (c) on the `confirm_receipt_upload` callback, inserts the `receipts` row and updates `storage_bytes_used` via trigger.
- Soft-cap warning: when crossing 500 MB, the next mutation by any member triggers an in-app banner on the admin's next session and an FCM push to the admin (subject to their notification prefs).
- **Client cache:** downloaded thumbnails and full images live only in Coil's disk cache with a 14-day TTL (`07 §10.1`); they are never persisted in Room and are re-fetched on demand after eviction.

## 9. Invariants summary (cross-table)

Implementing agents MUST be able to assert these at any time. They are also restated as test cases in `08-acceptance-criteria.md`.

1. `SUM(shares.share_owed_subunits FOR expense X) == expenses.amount_subunits` for non-deleted X.
2. `SUM(settlement_allocations.applied_amount_subunits FOR share S) <= shares.share_owed_subunits` for S.
3. `shares.remaining_subunits + SUM(applied_amount_subunits against this share) == share_owed_subunits` always.
4. `expenses.status == 'SETTLED'` iff `SUM(shares.remaining_subunits FOR expense X) == 0`.
5. For every `(group, currency)` pair, the bilateral balance ledger sums to zero: `SUM over pairs (A,B) of (A_owes_B - B_owes_A) == 0`.
6. A `conflicts` row exists iff its expense is non-equal-split AND a new member was added after the expense was created AND the conflict is unresolved.
7. `groups.admin_user_id IS NULL` iff the group has zero active members (abandoned).
8. `history_events` is append-only; the count for any expense is monotonically non-decreasing.
9. No `users.is_placeholder = true` row is ever referenced by `payment_app_handles`, `device_push_tokens`, or as a `groups.admin_user_id`.
10. After a successful `claim_placeholders`, no `users` row with `is_placeholder = true` exists for any of the merged IDs.
11. Every `receipts` row references exactly one parent: `(expense_id IS NOT NULL) XOR (draft_id IS NOT NULL)`.
12. The reserved system user (`00000000-0000-0000-0000-000000000000`) is never an active/left `members` row, never a `groups.admin_user_id`, and appears only as `created_by`/`payer_user_id` on auto-refund expenses (`03 §8.7`).
13. `groups.storage_bytes_used == SUM(receipts.byte_size + receipts.thumb_byte_size)` over non-deleted receipts in the group.

---

**Read next:** [`03-business-rules.md`](03-business-rules.md).
