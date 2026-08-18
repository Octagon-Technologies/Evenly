# Admin Dashboard & Feedback System — Spec

**Status:** §10 **steps 1, 2, 3, 4, 5 and 8 are built** (admin subdomain + gate, waitlist list and CSV,
growth chart, `feedback_tickets` + submit function + Slack + rate limiting, the thank-you screen on
both platforms, and the in-app feedback form). Steps 6, 7 and 9 are not. The SQL is written into
`supabase/schema.sql` but **has not been applied to the live project**, and Google OAuth, the Vercel
project and the Slack webhook are external setup that has not been done: `admin/README.md` is the
checklist.

**How this gets built:** one session per feature, not one session for the whole document. §10 lists
the split. The thank-you screen has its own brief already written
(`THANKYOU_SCREEN_PROMPT.md`) because it needs a visual mock agreed before any code, per the
project's mock-UI-first rule.

---

## 1. Scope

Two features behind one gate.

**A. Admin dashboard** on its own subdomain. Google sign-in, allowlisted. Two views: the waitlist
(who signed up, when, from where, with a growth chart) and the feedback queue (tickets, status,
triage).

**B. Feedback and support.** One form serving suggestions, bug reports and questions alike, reachable
from the app and the web, landing in Postgres and pinging a dedicated Slack channel.

### 1.1 Explicitly not building

These already have owners, and duplicating them is how a dashboard becomes a second job:

| Not building | Because |
| --- | --- |
| Crash reporting, stack traces, ANRs | Firebase Crashlytics |
| Product analytics, funnels, retention, feature flags | PostHog |
| Database metrics, query performance, log search | Supabase dashboard |
| Revenue, MRR, churn, subscription state | RevenueCat |

The dashboard covers **exactly two datasets**: `waitlist_signups` and feedback tickets. If a future
request opens with "while we're in here, could it also show…", the answer is a link to the tool that
already owns that number.

---

## 2. Admin access

**Decided:** Supabase Auth Google OAuth, gated by an **email allowlist table**, served from a
**dedicated subdomain**.

### 2.1 Allowlist by email, never by domain

Signing in with Google is necessary but not sufficient. A row in `admin_users` is what grants access.

**Domain-based gating is not an option here** and the reason is worth writing down so nobody
"simplifies" into it later: the owner account is a `gmail.com` address, and a domain rule on
`gmail.com` admits every Google account on earth. Domain gating only becomes viable if admin moves to
a Google Workspace domain, and even then explicit rows are cheap.

```sql
create table public.admin_users (
  user_id     uuid primary key references auth.users(id) on delete cascade,
  email       text not null,
  added_at    timestamptz not null default now(),
  added_by    uuid references auth.users(id)
);
```

Revoking an admin is deleting one row. No shared secret to rotate.

### 2.2 Subdomain, and its own deployment

**Decided:** `admin.split-evenly.app`, as a **separate Vercel project** rather than another route in
the marketing bundle.

The security argument is the real one. Today every visitor to `split-evenly.app` downloads the whole
JS bundle; if the admin dashboard lived in it, every visitor would be downloading admin code and its
queries too. Nothing is *exposed* by that on its own, but it hands an attacker a free map. A separate
project means the admin bundle is never served to a public visitor at all, which is a structural
guarantee rather than a policy.

Secondary benefits: admin cookies stay off the public origin, and admin deploys cannot break the
marketing site.

Cost: a second Vercel project and a second build. Some shared code (design tokens, the wordmark) gets
duplicated or extracted. **Open, minor:** duplicate or extract. Recommend duplicating at first, since
two small copies beat a shared package built for two consumers.

### 2.3 Where the gate actually lives

**Server-side, in an `admin` edge function. The browser check is cosmetic.**

This follows the boundary `web-claim` already set (`web/AGENTS.md`): no Supabase client and no anon
key in the bundle, every read through a function holding the service key. An admin dashboard reading
PostgREST directly with an anon key is a public data breach wearing a login form.

1. Browser does Google OAuth via Supabase Auth, receives a JWT.
2. Every request sends that JWT to the `admin` function.
3. The function verifies it, looks the user up in `admin_users`, and **returns 403 if absent, before
   touching any data**.
4. Only then does it query with the service key, returning exactly the shape the view needs.

`waitlist_signups` keeps its posture: RLS on, no policies, grants revoked. Nothing about it changes.

---

## 3. Waitlist view

### 3.1 The list

Email, source (`hero` / `band` / later others), signup time. Newest first. Search by email. **CSV
export**, because on launch day you will want this in a mail tool, and an admin panel you have to work
around is worse than none.

**No delete in v1.** Decided, with the reasoning recorded because it will come up again: an
unsubscribe flow needs somewhere for the person to *find* it, and before the first email is sent there
is no such surface. Building delete without an unsubscribe route means a button only you can press on
behalf of someone who has no way to ask. Revisit when the first campaign goes out; the natural
implementation is an unsubscribe link in the email footer carrying a signed token, at which point
delete has a front door and this row becomes easy.

### 3.2 The chart

- **Primary:** daily signups as bars, cumulative total as a line on the same axes. Daily bars show
  whether a campaign landed; the cumulative line shows whether the list compounds. Neither alone
  answers "how is this going".
- **Range:** 30 days default, with 7 / 30 / 90 / all toggles.
- **Breakdown by `source`**, which is the payoff for stamping it on every row from day one.
- **Empty and thin states matter.** Four data points must look deliberate, not broken. Pre-launch
  there will be genuine zero days.

Aggregation happens **in SQL inside the edge function** (`date_trunc` + `count`), never by shipping
every row to the browser to count there. Invisible at a thousand rows, five lines either way, so
there is no reason to write the version that stops working.

---

## 4. Feedback and support

One form, three entry points, one table.

### 4.1 Entry points

**Decided: all three, all in scope.**

| Where | Identity | Notes |
| --- | --- | --- |
| **In the app**, signed in | From the account | The primary path. No name or email fields; asking a signed-in user to type their own email is exactly the friction this feature exists to remove. |
| **`/feedback`** on the web | Anonymous | Reachable without installing anything, including for waitlist signups. Needs name/email fields and rate limiting (§8). |
| **Web claim flow** | Guest, maybe named | A quiet link in the spirit of spec §2.8's install footnote: one line, no card, nothing to dismiss. Someone who hit a problem claiming their dinner is precisely who you want to hear from. |

### 4.2 The form

Deliberately short. In order:

1. **Type** — Problem / Suggestion / Question. Drives the thank-you screen (§4.5) and triage.
2. **Category** — Payments, Sign-in & account, A missing or lost expense, Splitting & balances,
   The app's design, Something else.
3. **Message** — one text area, hard cap **100 words** (§4.3).
4. **Name** — optional, **web only**. Absent in-app entirely.

One tap, one tap, one paragraph. Everything else is inferred.

### 4.3 The word cap

**Decided: 100 words, hard cap.** The counter appears only on approach (~70 words) rather than
glaring from an empty box. Typing is blocked at the cap rather than silently truncating on submit;
losing someone's last sentence after they hit Send is a genuinely bad moment.

### 4.4 Auto-generated titles

**Decided: not in v1. If built later, server-side with Claude Haiku.**

Kimi (raised as a candidate) is **passed on**: reaching it needs another provider integration, which
is new surface and new failure modes for a cosmetic string. Haiku is already how `extract-receipt`
talks to a model, so it is one code path in a pattern that exists.

Why not on-device, recorded so it does not get re-litigated:

- **The title is never on the user's critical path.** They submit, see the thank-you, and leave. A
  title matters only when *you* read Slack or the queue. So generation must never block submission,
  which removes most of the case for local inference.
- **On-device coverage does not clear the bar.** Apple's Foundation Models framework needs iOS 26 and
  Apple Intelligence hardware; this project targets **iOS 16**, so on iOS it serves close to nobody.
  Gemini Nano needs AICore and specific devices. That is two `expect`/`actual` implementations plus a
  server fallback for the majority who qualify for neither: three paths for one cosmetic string, in a
  codebase whose first non-negotiable is that both platforms compile.
- **Truncation is a strong baseline.** `Payments · "I was charged twice for the same dinner and…"`
  scans as well as a generated title in a list of twenty.

The offline-in-a-restaurant worry is real and belongs somewhere else: make **submission** offline
tolerant (queue it, show the thank-you immediately, sync later), which is the existing outbox
pattern. Where a title is written is orthogonal.

### 4.5 The thank-you screen

Conditional on **type**, animated, and the one part of this feature worth real design time.

**This has its own brief: `THANKYOU_SCREEN_PROMPT.md`.** It carries draft copy for all three variants
and animation direction, and it asks for a mock to be agreed before any implementation. Do not build
it from this section.

The one thing fixed here, because it is a product commitment rather than a design choice: the Problem
variant may promise an email when the issue is resolved, because §5 makes that real.

---

## 5. Ticket lifecycle

Status, admin-only transitions: `new` → `in_progress` → `resolved`, plus `wont_fix` and `duplicate`
as terminal states.

Queue shows status, type, category, submitted-at, who (or "anonymous"), and the message. Filter by
status and category; **default view is `new` only**, because a queue that opens on everything ever
submitted stops getting opened.

Admin can change status and attach an internal note. **Notes are internal and never shown to the
submitter.** Stated plainly because that is exactly the kind of thing that leaks later.

### 5.1 Resolving notifies the submitter

**Decided: yes.** This is what makes the Problem thank-you copy honest.

**v1 is manual.** Resolving a ticket does not send anything automatically. The dashboard surfaces the
submitter's email with a copy button and the ticket's text, and you write the email yourself from your
own inbox. That keeps the first hundred replies human, which is when tone matters most and when you
are still learning what people actually write in.

**Later, an assisted drafter.** Once there is a reply style worth reproducing: a button that drafts an
email from the ticket plus an agreed base template, which you edit and send. Notes for whoever builds
it:

- **It drafts, it never sends.** A generated email leaving without a human reading it is a brand risk
  disproportionate to the time saved.
- **The agreed template is an input, not a prompt suffix.** Store the approved skeleton as data so it
  can be edited without a deploy.
- Needs a transactional sender (Resend, Postmark, or similar). Not chosen; not needed for v1.

Because v1 sends nothing automatically, no sender integration is required to ship this feature.

---

## 6. Slack

**Decided: a dedicated support channel, separate from ops.** A receipt-parser alert and a user saying
their expense vanished want different reactions from you, and a channel you have learned to skim is
worse than no channel.

Mechanism reuses what exists: `extract-receipt` and `revenuecat-webhook` both post via a webhook
helper, with an `ops_alerts` cooldown table behind it. Feedback uses the same helper against a
**second webhook URL** (new env var, e.g. `SLACK_FEEDBACK_WEBHOOK_URL`), inert if unset, same optional
config pattern as every other integration here.

One message per ticket: type, category, first ~30 words, and a deep link to the ticket in the
dashboard.

**No cooldown suppression on feedback.** The cooldown exists so one failing receipt scan cannot spam a
channel, but every ticket is a distinct human, and dropping the fifth because four arrived that minute
is silently losing your users' words. Inbound rate limiting (§8) is the right lever.

---

## 7. Data model (sketch)

Provisional shape; real DDL is written in the build session.

```sql
create table public.feedback_tickets (
  id              uuid primary key default gen_random_uuid(),
  -- Null for web/anonymous submissions. NOT a foreign key with cascade delete: if someone deletes
  -- their account you still want the bug report, so this holds the id and tolerates a dangling one.
  user_id         text,
  submitter_name  text,          -- web only, optional
  submitter_email text,          -- web only, for the reply
  type            text not null, -- problem | suggestion | question
  category        text not null,
  message         text not null,
  title           text,          -- null in v1 (§4.4)
  status          text not null default 'new',
  admin_note      text,          -- internal, never shown to the submitter
  source          text not null, -- app_ios | app_android | web | web_claim
  app_version     text,          -- app only
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);
```

RLS on, **no policies**, grants revoked, matching `waitlist_signups`. Both the submit function and the
admin function reach it with the service key.

`app_version` earns its place: half of triaging a bug is knowing whether it is already fixed.

**Note for the build session:** if this table is ever read *back* by the Kotlin client ("your past
tickets"), it becomes a synced entity and lands under `data/AGENTS.md`'s rules and the
schema-before-entity hook. v1 assumes **write-only from the client**, keeping it out of sync entirely.
Changing that is a much bigger decision than it looks.

---

## 8. Abuse and rate limiting

**Decided: per-IP rate limiting, 30 submissions per hour**, on the public unauthenticated endpoints
(waitlist signup and `/feedback`). One every two minutes sustained is already far past human, and 30
leaves ordinary use untouched.

- **Shared IPs are the thing to watch.** A café, office or campus NAT puts many real people behind one
  address. 30/hour is generous enough that this stays theoretical, which is what makes the number a
  good one. If it ever bites, the fix is a longer window rather than a lower cap.
- **In-app submissions are authenticated**, so rate-limit per user rather than per IP.
- Also cheap and worth having: a honeypot field on the web form, and the existing length caps.
- **Captcha only if genuinely abused.** It taxes every real user to stop a problem you may not have.
- Implementation should mirror `web_claim_write_log` / `receipt_scan_log`: an insert-only,
  service-role-only log table, counted in a window. That pattern exists twice already.

Other standing rules:

- **No PII in logs.** Never log message bodies or emails from an edge function.
- **The admin JWT check runs before any query**, never after (§2.3).
- Feedback messages are **user-controlled text rendered in your dashboard**. Escape on output; Svelte
  does by default. The note exists so nobody reaches for `{@html}`.

---

## 9. Decisions and what remains open

### Settled

| # | Decision |
| --- | --- |
| 1 | Category list confirmed, including **Splitting & balances** |
| 2 | Word cap **100** |
| 3 | Thank-you copy moves to `THANKYOU_SCREEN_PROMPT.md`, mock before code |
| 4 | Resolving **does** notify the submitter; manual in v1, assisted drafter later (§5.1) |
| 5 | **Dedicated Slack channel** for feedback, separate from ops |
| 6 | All three entry points in scope, claim flow included |
| 7 | Admin on its own **subdomain**, as a separate deployment |
| 8 | Kimi passed on; **Haiku** if titles are ever built |
| 9 | **No waitlist delete** in v1; revisit alongside unsubscribe |
| 10 | Admin gating by **email allowlist**, never domain (§2.1) |
| 11 | Public endpoints rate limited to **30/hour per IP** |

### Still open, all minor and none blocking

- ~~Duplicate or extract shared UI between the two deployments (§2.2).~~ **Settled in the build:
  duplicated.** The palette and the feather are copies; the marketing site's two variable fonts are
  not, since ~100KB of Fraunces to make an internal tool look editorial is not a trade worth making.
- ~~Whether `admin` is one edge function with sub-routes or several small ones.~~ **Settled in the
  build: one**, following `web-claim`, for a single JWT-verification path.
- Exact reply-time language in the thank-you copy. Belongs to the thank-you session.
- Transactional email provider, only when the assisted drafter is built (§5.1).

---

## 10. Build order, as separate sessions

**Built today:** a placeholder route only. `/admin` renders a stub stating the feature is not built,
and **makes no request and shows no data**. No auth, no query, no table behind it.

Each numbered item below is a good standalone session. They are ordered by dependency, and each is
independently shippable.

| # | Session | Touches |
| --- | --- | --- |
| 1 | ✅ Admin subdomain scaffold, Google OAuth, `admin_users`, the `admin` function's JWT gate | New Vercel project, Supabase |
| 2 | ✅ Waitlist list view + CSV export | Admin app, `admin` function |
| 3 | ✅ Waitlist growth chart | Admin app, SQL aggregation |
| 4 | ✅ `feedback_tickets` + submit function + Slack ping + rate limiting | Supabase |
| 5 | ✅ Thank-you screen, mock approved (`design/thankyou-screen-mockup.html`) then built: `FeedbackThankYou` in `FeedbackScreen.kt` (replaces the old placeholder) and `ThankYouScreen.svelte`, standalone | `:shared`, `web/` |
| 6 | Web `/feedback` form, wired to the step 5 thank-you screen | `web/` |
| 7 | Admin feedback queue and status transitions | Admin app |
| 8 | ✅ *(client only)* In-app feedback screen, Kotlin, both platforms, `ux-firsttimer` before done | `:shared` |
| 9 | *Optional, later:* assisted reply drafter (§5.1), auto-titles (§4.4) | Admin app, Supabase |

**Step 1 gates everything.** Nothing else should start until the gate is real, because every later
step reads through it.

**Step 6 still has nothing to wire to on the web side.** `ThankYouScreen.svelte` renders standalone
(no route, no form behind it) — step 5 was scoped to the screen itself, per its own brief
(`THANKYOU_SCREEN_PROMPT.md`), not the `/feedback` form that will eventually show it.

> **Status check, 2026-08-16.** The ticks above mean "written and committed", NOT "live". Against the
> project (`wfpfgbipjmkysalfmyub`), `feedback_tickets`, `public_write_log` and `admin_users` do **not
> exist**, and neither the `feedback` nor the `admin` edge function is deployed. Step 8's client is
> built and green on both platforms, and it queues every ticket locally because the endpoint it posts to
> answers 404. **Applying the schema and deploying the `feedback` function is what turns this feature
> on**; nothing else is blocking it.

Steps 1, 2, 3, 4, 6 and 7 are web and Postgres only. **Steps 5 and 8 are the ones that touch `:shared`**
and therefore the only ones that have to compile on both targets and clear the KMP parity rules.
