# Auth + onboarding polish (T1, T6, T8)

**Status:** not started
**Worktree / branch:** `fb/auth-onboarding` (from `feat/split-by-items-flow`)
**Mockup:** copy-level changes for T8/T6 — paste the before/after copy into the completion report and
get owner sign-off before committing; no new screens.

Paths below are under `code/shared/src/commonMain/kotlin/app/splitevenly/` unless noted. Read
`ui/AGENTS.md` and `data/AGENTS.md` first.

---

## T1 · Sign-up email carries both a magic link and a code

**Problem.** A tester signing up wanted to type a code because their email wasn't on the phone they
were signing up on. The app UI already promises both: `ui/screen/auth/MagicLinkScreen.kt:81` says
"We sent a magic link and a sign-in code" and `WiredScreens.kt:195` wires `verifyEmailOtp`. The likely
gap is server-side: `signInWith(OTP)` (`data/auth/SupabaseAuthSession.kt:193`) sends Supabase's
**"Confirm signup"** template to a brand-new user and the **"Magic Link"** template to an existing
one. If "Confirm signup" only contains `{{ .ConfirmationURL }}`, new users never see a code.

**User outcome.** Whether new or returning, the email has a tappable link *and* a 6-digit code, and
the code typed on any device signs them in.

**Non-goals.** No separate "send link" vs "send code" choice in the UI. No change to OAuth/Apple paths.

**Scout first (read-only), then decide.**
1. Confirm the hypothesis: sign up a fresh `+test` address (or the debug relay in
   `supabase/functions/debug-otp-relay/`) and read the email. Check which template fired.
2. Check `verifyEmailOtp` uses the right `OtpType` for a first-time user (`Email` works for both
   since GoTrue v2; if it uses `MagicLink` or `Signup` only, a new user's code is rejected).
3. Templates live on the hosted project, not in the repo (`supabase/config.toml:246` is commented).

**Acceptance criteria.**
- Given a never-seen email, when they tap "Email me a code", then the email contains a link and a
  sign-in code, and entering the code signs them in. Note the hosted project issues **8** digits while
  `config.toml:232` says 6; `MagicLinkScreen` already accepts 6–8 (`OTP_MIN/MAX_DIGITS`). Make the
  template and the field agree on the hosted value.
- Same for a returning email.
- Wrong/expired code → inline error under the field, field stays editable, "Resend code" live.

**Deliverable split.** Code fixes (OtpType, copy) are committed. The template change is a hosted
dashboard edit: write the exact HTML for "Confirm signup" and "Magic Link" into
`supabase/templates/` (new files, checked in as the source of truth) and list the dashboard step in
the completion report for the owner. Do not change the hosted project yourself.

---

## T6 · Editing payment details is obviously possible

**Problem.** Once handles are saved, the owner suspects people don't realize they can change them.
Today the only path is Profile → "Payment apps" (`ui/screen/settings/ProfileScreen.kt:158` →
`Route.PaymentHandles` → `ui/screen/settings/PaymentHandlesScreen.kt`).

**Open question for the planner (resolve by reading, then confirm with owner in the mock note):**
"payment details" is assumed to mean the user's own handles. If the scout finds a second surface
where a payer enters details (e.g. the settle-up or share flow in `ui/navigation/SettleRoutes.kt`),
list it; do not silently expand scope.

**Acceptance criteria.**
- The Profile row shows the saved handles' summary (e.g. "M-Pesa, Venmo") and a visible "Edit"
  affordance, not just a chevron; with none saved it reads "Add a payment app".
- On `PaymentHandlesScreen`, saved handles render as filled, editable fields with a visible save
  confirmation; the save button follows the never-silent-dead-end rule (AGENTS.md §7).
- Where the user's own handles appear on a settle-up screen, a small "Edit" link jumps to
  `Route.PaymentHandles`.

---

## T8 · Onboarding says why a handle matters

**Problem.** A tester didn't understand why the "handle" step (`ui/screen/auth/OnboardingScreen.kt`,
step `"handle"` at L77) wants at least one payment app.

**User outcome.** In one glance: "Add one so friends can pay you back in a tap."

**Acceptance criteria.**
- The handle step has a one-line rationale under the title, ≤ 12 words, plain language, no em dash
  (AGENTS.md §4.7), e.g. *"Friends see this when they settle up, so they can pay you in one tap."*
- A second muted line states the requirement plainly: *"Add at least one. You can change it later."*
- Continue with zero valid handles keeps the existing validate-on-tap behaviour (`handlesChecked`,
  L86) and the error names what is missing.

---

## Test plan

| Criterion | Proof |
| --- | --- |
| T1 OtpType / verify path | Unit test on the auth session mapping if logic changes; manual sign-up on the iOS sim with the debug relay |
| T6, T8 copy and affordances | iOS sim run + `ux-firsttimer` walked as Sam (invited friend) |
| Both targets | AGENTS.md §5 compile pair + `testAndroidHostTest` |

## 7. Completion report

_(fill in at the end)_
