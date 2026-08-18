# Evenly Admin

The internal dashboard: the waitlist today, the feedback queue later. Spec: `../ADMIN_FEEDBACK_SPEC.md`.

Svelte 5 + Vite, its own Vercel project at `admin.split-evenly.app`, separate from `web/` so a public
visitor never downloads admin code (spec §2.2).

```bash
cd admin && npm ci
npm run dev          # http://localhost:5178
npm run typecheck
npm run build
```

`?mock`, `?mock=thin`, `?mock=empty` skip auth entirely and serve a fixture, so the chart's thin and
empty states can be judged without waiting for real signups. Dev only, dropped from a build.

---

## Setup, once

Four steps, three of which need a human with the right logins. Nothing in this app works until step 1
and step 2 are done; **step 3 is the one that decides whether you personally can get in**.

### 1. Apply the SQL

`../supabase/schema.sql` gained `admin_users`, `feedback_tickets`, `public_write_log` and
`admin_waitlist_stats` at the bottom of the file. Run that block in the Supabase SQL editor (project
`wfpfgbipjmkysalfmyub`). It is additive and idempotent, so re-running it is safe.

### 2. Google OAuth in Supabase

1. Google Cloud console → APIs & Services → Credentials → **Create OAuth client ID**, type *Web
   application*.
2. Authorised redirect URI: `https://wfpfgbipjmkysalfmyub.supabase.co/auth/v1/callback`.
3. Supabase dashboard → Authentication → Providers → **Google** → paste the client ID and secret,
   enable.
4. Supabase dashboard → Authentication → URL Configuration → add `https://admin.split-evenly.app`
   and `http://localhost:5178` to **Redirect URLs**.

Until this is done the sign-in button starts a flow that Google refuses.

### 3. Put yourself on the allowlist

There is deliberately **no bootstrap path in code**. An "if the table is empty, trust this email"
branch in the edge function would be a permanent backdoor to save a one-time statement.

So: sign in once at the dashboard. You will land on **No access**, which is correct, and the attempt
creates your `auth.users` row. Then run:

```sql
insert into public.admin_users (user_id, email)
select id, email from auth.users where email = 'andrewchelimo2000@gmail.com'
on conflict (user_id) do nothing;
```

Reload. Revoking an admin later is `delete from public.admin_users where email = '…'`.

### 4. Deploy

```bash
supabase functions deploy admin
supabase functions deploy feedback
supabase functions deploy waitlist   # redeploy: it gained rate limiting
```

Vercel: **new project**, root directory `admin/`, framework preset Vite, domain
`admin.split-evenly.app`. Environment variables (all three, Production and Preview):

| Variable | Value |
| --- | --- |
| `VITE_ADMIN_FN_URL` | `https://wfpfgbipjmkysalfmyub.supabase.co/functions/v1/admin` |
| `VITE_SUPABASE_URL` | `https://wfpfgbipjmkysalfmyub.supabase.co` |
| `VITE_SUPABASE_ANON_KEY` | the project's anon/publishable key |

Copy `.env.example` to `.env.local` and fill the same three for local development.

### Edge function secrets

All optional, all inert when unset, same pattern as every other integration here.

| Secret | Function | Effect when unset |
| --- | --- | --- |
| `SLACK_FEEDBACK_WEBHOOK_URL` | `feedback` | No Slack post. The ticket is still stored. |
| `ADMIN_DASHBOARD_URL` | `feedback` | The Slack message carries no deep link. |
| `ADMIN_ALLOWED_ORIGINS` | `admin` | CORS allows any origin. Not a hole (auth is a bearer token, not a cookie), but set it to `https://admin.split-evenly.app,http://localhost:5178` anyway. |

Create the Slack webhook against a **dedicated support channel**, not the ops one (spec §6): a
receipt-parser alert and a user saying their expense vanished want different reactions from you.

---

## What is not built here

Build order (spec §10) steps 5 to 9. The feedback queue is step 7, and there is deliberately no
placeholder tab for it: a nav item that leads nowhere is worse than one that is not there yet.
