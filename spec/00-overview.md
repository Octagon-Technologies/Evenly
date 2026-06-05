# 00 — Overview

> **Status:** Draft v1 — Spec for ShareCost mobile app (iOS + Android), backed by Supabase.
> **Audience:** Implementing agents (Claude / Codex) and human reviewers. High-fidelity, normative.
> **Source brief:** [App_Overview.md](../App_Overview.md).

---

## 1. What this spec is

A complete, agent-implementable specification for **ShareCost v1**: a free shared-expense tracker that records bilateral balances and hands settlement off to the user's own payment app. ShareCost never custodies money and never asks for a bank account.

Each numbered file below covers one concern. Files are independently loadable: an implementing agent can be told "implement section 03 §4 (auto-refund)" and have enough context in `03-business-rules.md` plus the linked sections of `02-data-model.md` to do the job.

| # | File | Concern |
|---|---|---|
| 00 | `00-overview.md` | This file — orientation, conventions, decisions log |
| 01 | `01-glossary-and-domain-model.md` | Terms, entities, relationships, identity model |
| 02 | `02-data-model.md` | Postgres schema, Room KMP schema, indices, RLS, invariants |
| 03 | `03-business-rules.md` | Deterministic logic: splits, balances, settlement, refunds, conflicts, FX, history |
| 04 | `04-api-and-sync.md` | Supabase RPCs, Edge Functions, Realtime, Storage, local mutation queue |
| 05 | `05-ux-screens.md` | Screen-by-screen spec, navigation graph, states |
| 06 | `06-architecture-and-stack.md` | KMP/CMP module layout, libraries, expect/actual surfaces |
| 07 | `07-non-functional.md` | Performance, security, accessibility, observability, testing |
| 08 | `08-acceptance-criteria.md` | Testable behaviors organized by milestone |
| 09 | `09-open-questions.md` | Unresolved items and explicit deferrals |

---

## 2. Goals (v1)

1. **Free, uncapped, ad-free** shared-expense tracking with no entry timer.
2. **Individual-expense settlement**: pay one expense at a time, not just whole balances.
3. **Settle via the user's own payment app** through deep links — no bank link, no KYC.
4. **Retroactive member addition** with conflict tab and auto-refunds.
5. **Per-expense conversation threads, history log, refunds** linked to the original expense.
6. **Multi-currency** with daily FX refresh; bilateral balances never simplified.
7. **Offline-first**: every read and most writes succeed without connectivity; sync on reconnect.
8. **Accessible**: full VoiceOver and TalkBack support; meets WCAG 2.1 AA color contrast.

## 3. Non-goals (v1)

These are spec-binding negatives; implementing them is out of scope and adding their surfaces is a policy violation:

- No bank account linking, KYC, or money custody. **Ever.**
- No debt simplification or automatic netting across the group. **Ever.**
- No automated debt reminders to other users. **Ever.**
- No subscription tier of any kind in v1.
- No web client in v1 (deferred to v2).
- No international payment app integrations (M-Pesa, UPI, SEPA) in v1; US-focused.
- No receipt OCR in v1.
- No multi-language UI in v1; English only.
- No recurring expenses in v1 (v2 candidate, see App_Overview §11).

## 4. Platforms & stack (v1)

- **Mobile:** Kotlin Multiplatform (KMP) for shared logic + Compose Multiplatform (CMP) for shared UI.
- **Targets:** Android (minSdk 26, targetSdk 35), iOS (iOS 16+).
- **Backend:** Supabase (Postgres + Auth + Storage + Realtime + Edge Functions).
- **Local DB:** Room KMP (officially supported, sufficient maturity on iOS).
- **Networking:** Ktor client (used by `supabase-kt`).
- **DI:** Koin.
- **Date/time:** `kotlinx-datetime`.
- **Money:** Long subunit values + ISO 4217 currency code (see §6 below and `03-business-rules.md §1`).
- **Push:** FCM for Android directly; FCM-to-APNs for iOS via Supabase Edge Function fan-out.
- **Analytics & crash reporting:** Firebase Analytics + Firebase Crashlytics (consent-gated, anonymous, ad-ID disabled — D-23).
- **FX provider:** Frankfurter (ECB-backed, no API key).

Full library list, module layout, and expect/actual surfaces in `06-architecture-and-stack.md`.

## 5. Top-level decisions log

These are settled. Any deviation must be raised before implementation.

| # | Decision | Rationale | Reference |
|---|---|---|---|
| D-01 | Account required for self-use; **placeholder participants** allowed for non-account members | Data-loss risk of anonymous identities is unacceptable; placeholders preserve the "log Tyler quickly" UX without it | `01 §4`, `03 §7` |
| D-02 | Bilateral balances never netted, never simplified | Brand principle and table-stakes differentiator | `03 §3` |
| D-03 | Each expense tracks per-participant `share_owed_subunits` and `remaining_subunits` | Enables individual-expense settlement, the headline differentiator | `02 §3.4`, `03 §1–2` |
| D-04 | Multi-currency: balances stored per currency, never converted at rest | Cross-FX settlement uses live conversion at the moment of settlement | `03 §6` |
| D-05 | Multi-expense settlement across mixed currencies: payment denominated in **USD** in v1 | Simpler in v1; user-home-currency is a v2 follow-up | `03 §4.3` |
| D-06 | FX cache: build-time baked snapshot + daily Frankfurter refresh + Room cache | Works on first launch without network; degrades gracefully | `03 §6.1`, `04 §5` |
| D-07 | FX staleness signal shown only when >7 days old | Minimal noise; surfaces at the point trust matters | `03 §6.3` |
| D-08 | First-launch FX fetch failure: silent fallback to baked snapshot | First-launch friction outweighs FX inaccuracy | `03 §6.4` |
| D-09 | Per-group storage: **soft 500 MB, hard 1 GB** of receipt attachments | Bound storage costs; admin can prune via export | `03 §8`, `07 §1` |
| D-10 | Settled-tab default sort: by **original expense date**, most recent first | Consistent with Active tab chronology | `05 §6.2` |
| D-11 | Archived group is hidden **and** silenced | Matches WhatsApp/Telegram archive model; user must unarchive to see updates | `05 §3.4`, `04 §6.2` |
| D-12 | Invite link: persistent, admin-rotatable, QR encodes the URL | Matches WhatsApp model; lowest friction | `03 §7.1`, `04 §3.3` |
| D-13 | Payment-app deep link with clipboard fallback on failure | Best-effort; no user dead-ends | `03 §5`, `05 §8` |
| D-14 | Auth: Google + Apple + Facebook OAuth + email magic link | Apple is App Store-mandatory; magic link covers no-social users | `04 §1` |
| D-15 | Sync: server-authoritative LWW on `updated_at`; client mutation queue | Simplest model that meets correctness; ledger semantics survive LWW | `04 §6`, `07 §5` |
| D-16 | Local store: Room KMP | Per user direction; AndroidX/Jetpack support, iOS via KMP | `06 §2` |
| D-17 | Money: `Long` subunit values; even-split remainder distributed cent-by-cent in member-join-date order | Determinism; displayed total == entered total | `03 §1` |
| D-18 | History log entries are append-only; never edited or deleted | Trust anchor; foundation of the retro-add UX | `03 §9` |
| D-19 | Comment thread visibility: participants of the expense only | Privacy default; matches the social texture of bilateral debt | `03 §10` |
| D-20 | All IDs are UUIDv7 (time-ordered) | Improves sync ordering and index locality | `02 §2` |
| D-21 | Itemized split carries **tax + tip**: `tip_subunits` + `tip_split_mode` (`PROPORTIONAL`/`EVEN`) | Real US restaurant bills split tax AND tip (OQ-05) | `02 §3.7`, `03 §1.4`, `05 §5.2` |
| D-22 | Receipts uploaded **compressed (≥80%)** + client thumbnail; **lazy fetch**; **14-day** Coil TTL | Bound Storage + on-device footprint (OQ-11) | `02 §3.10`, `02 §8`, `03 §15`, `07 §10.1` |
| D-23 | Use **Firebase Analytics + Crashlytics** for events + crashes (ad-ID off) | Per user direction (OQ-08); replaces the prior no-Google-analytics stance | `06 §2.2`, `07 §2.7`, `07 §4` |
| D-24 | **Server-synced expense drafts** in v1, incl. receipt-first | Capture-now-split-later without data loss across devices (OQ-13) | `02 §3.17`, `03 §3.5`, `04`, `05 §5.5` |
| D-25 | Anonymous telemetry is **opt-in, default ON**, set in onboarding + Profile → Privacy | Trust + store disclosures (OQ-07) | `05 §1.3`, `07 §4.3` |
| D-26 | Receipt deletion is **author-only** | Prevents one member deleting another's photo (OQ-06) | `02 §3.10`, `04 §2.3` |
| D-27 | Base currency defaults to **USD** at signup but the user's chosen base currency drives display; new groups default to the **creator's** base currency | Don't force USD on non-US users (OQ-04) | `01 §4.1`, `02 §3.4`, `05 §1.3` |

## 6. Conventions

- **Money** is represented as `(amount_subunits: Long, currency: String)` where `currency` is an ISO 4217 code (e.g., `"USD"`, `"JPY"`). The currency's `minor_unit` scale (cents = 2, JPY = 0) is looked up from a static table; see `02 §3.1`.
- **Timestamps** are `Instant` (UTC) in the schema. Display time zone = device local. Pure **dates** (e.g., expense date) are `LocalDate` and have no timezone — they are entered in the user's local zone but stored as a calendar date.
- **IDs** are UUIDv7 strings rendered as canonical 36-char form.
- **Enums** in code and SQL match exactly; SQL uses `text` columns with `CHECK (value in (...))` constraints, not Postgres enums, so adding a value does not require a migration cycle.
- **Soft-deletes**: rows that participate in history have a `deleted_at: Instant?`. Hard delete is reserved for compliance flows.
- **Snake_case** in SQL, **camelCase** in Kotlin. The mapping is mechanical.
- **RFC keywords** ("MUST", "SHOULD", "MAY") have RFC 2119 meanings throughout this spec.
- **Linking convention** between spec files: `\`02 §3.4\`` means "file `02-data-model.md`, section 3.4."

## 7. Implementation milestones (mapped to acceptance criteria)

The spec is sliced into five buildable milestones in `08-acceptance-criteria.md`. Order matters; each builds on the previous.

1. **M1 — Identity & groups:** Auth (D-14), onboarding (base currency D-27, telemetry consent D-25), group create/join/leave, members, placeholder participants.
2. **M2 — Expenses & balances:** Add/edit/delete expense, splits (D-17), itemized tax+tip (D-21), server-synced drafts (D-24), receipts compress/thumbnail/lazy-fetch + author-only delete (D-22, D-26), bilateral balance computation (D-02, D-03), FX (D-04, D-06–08).
3. **M3 — Settlement & refunds:** Individual + multi-expense settlement (D-05), payment-app deep links (D-13), refunds linked to expenses.
4. **M4 — Retro adds & conflicts:** Retroactive member addition, conflict tab, auto-refunds, history log (D-18).
5. **M5 — Polish & operational:** Comments (D-19), per-expense history UI, push (FCM + APNs), Firebase Analytics + Crashlytics (D-23), exports, archive (D-11) + one-time auto-unarchive tooltip, accessibility pass.

Each milestone has an explicit list of acceptance tests in `08-acceptance-criteria.md`. A milestone is "done" iff all its tests pass on both Android and iOS.

## 8. Out-of-scope creep checklist

Implementing agents MUST NOT introduce, without explicit user sign-off, any of:

- A bank-link, plaid integration, or KYC step.
- A "simplify balances" or "minimize transactions" button.
- An auto-reminder ("nudge Bob") to other users.
- Hidden/silent recompute of past expenses outside the conflict tab (retro adds for non-equal splits MUST land in the conflict tab).
- Cross-group debt netting.
- A paid tier, ads, or storage-cap monetization.
- Receipt OCR.
- Any non-USD payment-app integration (Wise, Revolut, M-Pesa, UPI, SEPA, IBAN).

If a feature is ambiguous, default to the more restrictive interpretation and flag it in `09-open-questions.md`.

---

**Read next:** [`01-glossary-and-domain-model.md`](01-glossary-and-domain-model.md).
