# Evenly

A free shared-expense tracker that records who owes whom, and hands settlement off to
whichever payment app you already use. Built for groups who keep owing each other small
amounts of money — trip companions, roommates, couples, recurring social groups — Evenly
tracks bilateral balances without ever custodying money or asking for a bank account.

Live at [split-evenly.app](https://split-evenly.app). Android and iOS, plus a no-install web
flow for guests.

## What it does

- **Groups and expenses.** Create unlimited groups, add expenses with amount, payer,
  participants, date, category, and receipt photos/PDFs. A built-in calculator sits on the
  amount field, and the app remembers your last-used payer, split mode, and participants per
  group.
- **Flexible splitting.** Even split, by share, by percentage, by exact amount, or
  itemized — assign individual receipt line items to the people who actually ordered them.
- **Real-time bilateral balances.** Always shows "X owes Y," never netted or simplified
  away, so nobody loses track of a debt.
- **Claim links for non-users.** A payer can send a link or QR code that lets a guest open
  the bill in a browser, claim the items they owe for, and leave as a real group member —
  no app install, no account, no password.
- **Multi-currency.** Per-expense currency override with daily FX refresh (ECB-backed,
  cached offline, seeded with a build-time snapshot so conversions work before the first
  network call).
- **Offline-first.** Expenses entered without connectivity sync automatically on
  reconnection; conflicting edits are merged field-by-field rather than dropped or
  overwritten.
- **Receipt OCR.** Snap or upload a receipt and the app extracts line items and prices
  automatically as a starting point for itemized splits.
- **Push notifications, dark mode, per-group archive, admin auto-transfer, and more.**

## Tech stack

**Mobile app** — Kotlin Multiplatform, targeting Android and iOS from one codebase
(`app.splitevenly`):
- **UI:** Compose Multiplatform, shared across both platforms
- **Persistence:** Room (KMP) with an offline outbox and background sync
- **Networking/backend client:** Ktor + Supabase Kotlin SDK (Postgres, Auth, Realtime,
  Storage)
- **DI:** Koin · **Async:** Kotlin Coroutines · **Logging:** Kermit
- **Background work:** WorkManager (Android) / background `URLSession` (iOS) for resumable
  receipt uploads
- **Analytics:** PostHog · **Crash reporting / push:** Firebase (Android host)

**Backend** — Supabase (Postgres with row-level security, edge functions, Realtime) as the
sync and auth layer; no custom server to operate.

**Web claim surface** — a separate Vite/Svelte + TypeScript bundle that reimplements the
core split-math engine in TypeScript so guests can claim a bill in a browser with zero
backend round-trips for the math itself. A shared JSON test-vector suite keeps the Kotlin
and TypeScript engines numerically identical.

**Cross-cutting practices:** both platforms are required to build green before anything
ships; user data is soft-deleted (tombstoned, never dropped) so deletions sync correctly
across devices; schema changes land server-side before the client code that depends on them.
