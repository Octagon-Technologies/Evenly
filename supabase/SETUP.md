# Supabase setup — what to supply and where

The app ships **local-first** and runs fully offline on a placeholder config. Flipping in real
credentials switches DI to the live Supabase auth + sync path (`authModule`, `06 §5.5`). Three steps:

## 1. Paste your project credentials

Edit **`code/shared/src/commonMain/kotlin/da/chelimo/sharecost/data/remote/supabase/SupabaseConfig.kt`**
and replace the two placeholders:

```kotlin
object SupabaseConfig {
    const val URL: String = "https://YOUR_PROJECT_REF.supabase.co"   // ← Project URL
    const val ANON_KEY: String = "YOUR_SUPABASE_ANON_KEY"            // ← anon / public key
}
```

Find both in the Supabase dashboard → **Project Settings → API**:
- **URL** = "Project URL".
- **ANON_KEY** = "Project API keys" → the **`anon` `public`** key (NOT the `service_role` key — never ship that).

`SupabaseConfig.isConfigured` flips to `true` automatically once these no longer contain the
placeholder text, and the app boots into Supabase mode on next launch. No code change beyond these two
lines.

> Prefer not to commit secrets? The anon key is a public, RLS-gated key and is safe in the client, so
> committing it is acceptable. If you'd rather inject it, wire these two constants from `BuildConfig` /
> an env var in your build — `SupabaseConfig` is the single source of truth the rest of the app reads.

## 2. Enable Anonymous sign-ins

MVP sign-in uses Supabase **anonymous auth** (it yields a real session keyed by a server user id,
which everything is keyed on, with no email round-trip). Enable it:

Dashboard → **Authentication → Providers → Anonymous** → toggle **on**.

(Email magic-link is available in `supabase-kt` for a later step but needs an OTP-verify screen; the
current `MagicLinkScreen` flow drives the anonymous path.)

## 3. Create the schema

Run **`supabase/schema.sql`** (this folder) in the dashboard → **SQL Editor**. It creates the seven
synced tables whose columns mirror the Room `@Entity` rows 1:1, and enables Row-Level Security.

> ⚠️ The RLS policies in `schema.sql` are **permissive** (`to authenticated using (true)`) so sync
> works end-to-end immediately for testing. Before any real multi-user use, tighten them to
> membership-scoped policies (a row is visible only to members of its group) per `spec/04` — a sketch
> is in the comments at the bottom of `schema.sql`.

## What you get

- **Auth**: `SupabaseAuthSession` — sign-in creates/restores a real session; sign-out ends it.
- **Sync**: `SyncEngine` — on sign-in it **pushes** the device's local rows up, then **pulls** the
  user's groups + everything under them (members, expenses, shares, settlements, conflicts, users)
  back into Room. Last-write-wins on whole rows (field-level merge / realtime deltas are later work).

## Not yet wired (future)

- Server-side RPCs (`add_expense`, `edit_expense`, `apply_settlement`, `claim_placeholders`,
  `resolve_conflict`, `join_group_by_token` — `spec/04 §2.3`). The current sync upserts rows directly;
  the RPC path (atomic server-side validation) is the S-1+ refinement.
- Realtime subscriptions, Storage (receipts), and the conflict-reminder Edge Function.
- Membership-scoped RLS (see the warning above).
