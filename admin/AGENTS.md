# `admin/` — the internal dashboard

Governs `admin/**`. Spec: `../ADMIN_FEEDBACK_SPEC.md`. Setup and deployment: `README.md` next door.

A second Svelte/Vite bundle, deployed as its own Vercel project at `admin.split-evenly.app`. It is
**not** a route in `web/`, and the reason is structural rather than stylistic: every visitor to
split-evenly.app downloads that whole bundle, so admin code living in it would hand an attacker a
free map of the queries and shapes worth attacking. Separate projects mean the admin bundle is never
served to a public visitor at all.

## The gate is on the server. Everything in this directory is cosmetic.

Access is decided in the `admin` edge function: verify the JWT against the auth server, look the user
up in `admin_users`, **403 before touching any table** (spec §2.3). Every admin table has RLS on with
no policies and grants revoked from `anon`/`authenticated`, so the service key inside that function is
the only thing that can read them.

- **`src/lib/auth.ts` is the only Supabase client in either web bundle, and its only job is OAuth.**
  It never reads a table. A `supabase.from(...).select()` added there returns `42501 permission
  denied`, and the fact that it would is the point. If you find yourself wanting one, the answer is a
  new action in the `admin` function.
- **There is no `isAdmin` flag in this bundle**, deliberately. Nothing here is worth tampering with in
  a console, because editing it gets you different pixels and no data.
- **Allowlist by email, never by domain** (spec §2.1). The owner account is a `gmail.com` address, so
  a domain rule admits every Google account on earth.
- **No bootstrap in code.** The first `admin_users` row is inserted by hand (`README.md` step 3). An
  "if the table is empty, trust this env var" branch is a permanent backdoor to save one statement.

## Rules that look like preferences and are not

- **No waitlist delete** (spec §3.1). An unsubscribe flow needs somewhere for the person to *find* it,
  and before the first campaign there is no such surface, so delete would be a button only the owner
  can press on behalf of someone with no way to ask. Revisit alongside the unsubscribe link.
- **Aggregate in SQL, never in the browser.** The chart's numbers come from `admin_waitlist_stats`,
  which does its own `date_trunc` + `count` and returns a **dense** day series so zero days are drawn
  rather than skipped. Nothing in `src/` counts rows.
- **Feedback messages are user-controlled text rendered in your own dashboard.** Svelte escapes on
  output by default. Do not reach for `{@html}` on anything from `feedback_tickets`.
- **Admin notes are internal and never shown to the submitter** (spec §5). Stated here because that is
  exactly the kind of thing that leaks once someone builds a "your ticket" view.
- **`?mock` is dev-only and guarded by `import.meta.env.DEV`** at every call site, so `mock.ts` is
  unreachable in a build. It exists because the states that matter most pre-launch are the ones with
  no data: four points and a run of zero days have to look deliberate, and you can only judge that by
  looking.

## Duplication, on purpose

The palette in `src/admin.css` and the feather in `src/components/Wordmark.svelte` are copies of the
marketing bundle's, not a shared package (spec §2.2, settled: two small copies beat a package built
for two consumers). Selectors are `adm-`-prefixed against the sibling's `site-`, so a component
copied between the two cannot collide.

**As of 2026-08-22, Rethink Sans (only, not Fraunces) is copied too**, for the gate screens
(sign-in, no-access, and their loading/unconfigured/error siblings, all sharing `.adm-gate`) and the
top bar's brand mark — self-hosted from the same `rethink-var.woff2`, not a Google Fonts request.
Reverses the original "system fonts here" call: those screens are the only thing a stranger who
finds the subdomain ever sees, and the owner judged plain system type there as reading unfinished
rather than restrained. The chart and table stay on system fonts; body copy at that density has
nothing to gain from a display face.

The gate screens themselves are deliberately undecorated — no card, no border, no glow — modelled on
Vercel's, Supabase's and GitHub's own sign-in pages rather than on `web/`'s bloom-card waitlist
system: the first mockup copied the waitlist's card treatment directly and was rejected for looking
over-decorated on a one-button utility screen. Restraint over ornament is the decision, and the
reason it looks unlike `web/`'s waitlist page on purpose.

The feather's single source of truth is `EvWordmark.kt`. This copy and `web/`'s move with it.

## Toolchain

Node ≥ 22.6. One runtime dependency, `@supabase/supabase-js`, for OAuth. `web/` has zero and should
keep it that way; this one has exactly the one it cannot avoid.

```bash
cd admin && npm ci && npm run typecheck && npm run build
```

CI is `.github/workflows/admin-build.yml`, on `admin/**`: `npm ci`, `typecheck`, `build`. Its own
workflow rather than a job in `money-vectors.yml`, which exists to catch drift between the two copies
of the split math and has nothing to do with this bundle.

There is no test suite here and that is not an oversight: this bundle holds no money math and no
authorisation logic. Both live where they are tested. If logic that decides anything appears in
`src/`, it is in the wrong bundle.
