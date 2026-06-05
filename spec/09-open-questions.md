# 09 — Open Questions (RESOLVED)

> All items below have been **answered by the user and applied across the spec.** Each item retains its original framing for context; the user's answer is under **Answer:**, and **Applied:** records exactly what changed and where. New decision IDs (D-21…D-27) are logged in `00 §5`.
>
> The five App_Overview Section-10 questions were resolved during this spec's design — they are NOT relisted here.

---

## OQ-01 — Invite link rotation: admin-only or any member?

**Status:** Conflicting language in spec.

`03-business-rules.md` and `05-ux-screens.md` show "Rotate invite link" as an admin-only affordance (matching D-12's "admin can rotate"). `08-acceptance-criteria.md` AC-M1-012 asserts non-admin gets `NOT_ADMIN`. But `01-glossary-and-domain-model.md §7` ("Permissions model") lists token rotation as admin-only and explicitly contrasts with member-management (any member). This is internally consistent — admin-only.

**Why it matters:** If admin-only, a non-admin who suspects the link leaked has no remedy except asking the admin.

**Proposal:** Keep admin-only for v1. Add to v1.1 if leak-without-admin-availability becomes a real complaint.

**Unblock by:** User confirmation. If user wants any-member rotation, change `04 §2.3` `rotate_invite_token` to drop the admin check and update AC-M1-012 to "any active member can rotate."

Answer: Rotation of invite tokens is by admin only.

**Applied:** Admin-only is now stated consistently. `04 §2.3` `rotate_invite_token` documents the `NOT_ADMIN` check + token invalidation + `invite_token_rotated_at` stamp. `08` AC-M1-012 cleaned up (removed the stale "§9.3 says any member" parenthetical, which was never true) and extended to assert old-token invalidation.

---

## OQ-02 — Universal-link deep entry into archived group: auto-unarchive?

**Status:** Spec resolves it (`05 §11`), but the call deserves explicit review.

D-11 silences and hides archived groups. `05 §11` says navigating to an archived group via universal/deep link auto-unarchives it as a side effect. The reasoning: the user clearly wants to see this group.

**Why it matters:** Surprises a user who archived and then mistakenly tapped a 6-month-old email link.

**Proposal:** Add a one-time tooltip on the first auto-unarchive event explaining "We unarchived [group] because you opened a link to it. Re-archive any time." This is in `05 §11` as informative.

**Unblock by:** Confirming the auto-unarchive plus tooltip pattern is acceptable. If not, the alternative is to keep the group archived and show a "Unarchive to view" CTA on the deep link landing.

Answer: Show the one time tooltip on the first auto-unarchive event

**Applied:** `05 §11` archive prose rewritten as normative (pushes never reach archived members; active open via universal link or Archived row auto-unarchives; one-time tooltip on the first auto-unarchive, tracked by a local one-shot flag, shown once per user not per group). New AC-M1-044 in `08`.

---

## OQ-03 — Categories: per-group or per-user?

**Status:** Spec assumes per-group (`02 §3.6`).

The App_Overview says "Users can add, edit, or delete categories" — could be interpreted as per-user (preferences travel across groups) or per-group (each group has its own tree, customizable by members).

Per-group is the simpler model and matches multi-currency / multi-group semantics. Per-user requires a cross-group categorization layer + reconciliation when joining a new group.

**Why it matters:** A user in 5 groups would have to re-create their "Pet" category 5 times if per-group. Acceptable for v1, irritating long term.

**Proposal:** Per-group in v1 (specced). Per-user template that auto-seeds on group create is a v1.1 enhancement.

**Unblock by:** User confirmation to keep per-group, or shift to per-user (which requires a larger redesign — touches `01 §1`, `02 §3.6`, RLS, and the seeding RPC).

Answer: I think we can keep it to per group in v1, solely because, if Bob classifies La Placita (in Puerto rico) as Entertainment > Nightlife, then that is prolly how everyone wants to classify it. It would beat logic to have everyone move it from other to nightlife, since that is too redundant.

**Applied:** No change needed — per-group categories are already specced (`02 §3.6`, `03 §11`). Confirmed.

---

## OQ-04 — Default base currency: USD globally or user-locale-derived?

**Status:** Spec defaults to USD.

`users.base_currency DEFAULT 'USD'`. v1 is US-focused (per App_Overview §11), so USD is the right default. But the app supports multi-currency from day 1; a user in the UK creating an account gets USD by default and has to change it.

**Why it matters:** Friction for non-US users in v1 (who exist even if "US-focused").

**Proposal:** Add a one-time onboarding step "Your default currency" that pre-fills USD and lets the user change it. Spec lightly mentions this in `05 §1.3` ("base currency"). Implementing agents MUST make this an explicit step, not buried.

**Unblock by:** Confirming the explicit onboarding step is acceptable.

Answer: Yes, during sign up, it should default to USD, but allow the user to change this in the app settings. The selected currency should be the base currency instead of forcing USD on everyone.

**Applied (D-27):** Onboarding has an explicit base-currency step pre-filled with USD (`05 §1.3`); it's editable in Profile → Base currency. The chosen `users.base_currency` drives display rollups. **Expert extension:** new groups now default their `base_currency` to the **creator's** base currency (not hardcoded USD) — `01 §4.1`, `02 §3.4`, `04 create_group`, `05 §2.1`. New AC-M1-008.

---

## OQ-05 — Tax handling: tax-only or also tip?

**Status:** Spec covers tax only.

`03 §1.4` itemized split has a tax row only. Real US restaurants split a bill with tax AND tip; tip is usually shared evenly or proportionally.

**Why it matters:** Forcing users to model tip as a separate expense or to fold it into pre-tax subtotals is friction.

**Proposal:** Add a `tip_subunits` field next to `tax_subunits` and a `tip_split_mode` enum (`PROPORTIONAL` or `EVEN`). This is a small extension; implementing agents should add it during M2 if user agrees.

**Unblock by:** User decision to add tip handling in v1 or defer to v1.1.

Answer: Add both tip_subunits and tip_split_mode in v1

**Applied (D-21):** `02 §3.7` adds `tip_subunits` + `tip_split_mode ('PROPORTIONAL'|'EVEN')` with a CHECK that tax/tip are zero unless `has_tax_row`. `03 §1.4` rewritten with the `itemizedShares` algorithm (tax always proportional; tip per mode). UI in `05 §5.2` (tip field + Proportional/Even toggle). ACs M2-016…018, AC-INV-012.

---

## OQ-06 — Receipt deletion: any member or author only?

**Status:** Not pinned in spec.

`02 §3.10` shows `uploaded_by`. `04 §2.3` shows `delete_receipt(receipt_id)`. The RLS policy in `02 §5.2` says "any active member" can update, which includes soft-delete.

**Why it matters:** Trust — letting any member delete another's photo is a vector for abuse.

**Proposal:** Author-only deletion. Update `04 §2.3` `delete_receipt` to require `uploaded_by = auth.uid()`. Add AC-M2-014 covering this.

**Unblock by:** User confirmation. Author-only is the safer default; if "any member" is desired (matching the spec's general "anyone can edit any expense" stance), keep current.

Answer: Only the author can delete a receipt

**Applied (D-26):** `04 §2.3` `delete_receipt` requires `uploaded_by = auth.uid()` (`NOT_AUTHORIZED` otherwise); RLS note in `02 §5`; permissions row in `01 §7`; UI hides delete for non-authors (`05 §4.1`). New AC-M2-013. New error code `NOT_AUTHORIZED` added to `04 §2.2`.

---

## OQ-07 — Telemetry: opt-in or opt-out?

**Status:** Spec implies opt-out (`07 §4.1` describes telemetry as default-on with no PII).

Modern app expectations lean opt-in; App Store / Play Store data-collection disclosures benefit from explicit consent.

**Why it matters:** Trust, App Store review surface.

**Proposal:** Default ON for anonymous telemetry (`07 §6.3` privacy section), with a clear toggle in Profile → Privacy. The single explicit consent is the privacy policy at sign-up. If User prefers opt-in, add a toggle in onboarding (`05 §1.3`) and default to off.

**Unblock by:** User decision.

Answer: Opt in for anonymous data collection should be shown during onboarding. It should default to yes, but allow the user to opt out. 

**Applied (D-25):** Onboarding step 4 is a default-ON anonymous-analytics toggle (`05 §1.3`); mirrored in Profile → Privacy (`05 §10`). The toggle gates Firebase Analytics + Crashlytics collection at runtime (`07 §4.3`, `06 §5.10`). ACs M1-007, M5-060/061.

---

## OQ-08 — Crash reporting in v1?

**Status:** Spec defers.

`07 §2.7` says no Crashlytics, no Sentry, no third-party crash SDK in v1. Catches go to Supabase Edge Function logs (server-side errors only) and an opt-in feedback diagnostics blob.

**Why it matters:** Without crash reporting, root-causing field issues is hard.

**Proposal:** Add a minimal first-party crash reporter that writes to a `crash_reports` Supabase table on next app launch (best-effort; no third-party SDK). Off by default; on via Profile → Privacy.

**Unblock by:** User decision. The minimal first-party reporter is the recommended path; Sentry is the lowest-friction alternative.

Answer: I want you to use firebase analytics and crashlytics for logging crashes and events.

**Applied (D-23):** Reverses the prior "no Analytics/Crashlytics" stance. `07 §2.7` now permits Firebase Analytics + Crashlytics; `07 §4.1/§4.3/§4.4` route events to Analytics and crashes to Crashlytics, consent-gated, no PII. The custom `ingest_telemetry` Edge Function is dropped. `06 §2.2/§2.3` add the SDKs + Gradle plugins; `06 §5.10` adds `Analytics`/`CrashReporter` facades. `00 §4` stack + D-23. **Ad-ID collection disabled** to keep the "no advertising ID, ever" promise (`07 §9`).

---

## OQ-09 — App-Store-Connect screenshots: what flow?

**Status:** Out of spec scope, listed here so it's tracked.

The app needs marketing screenshots at multiple device sizes for both stores. The Trip Overview export is the closest thing the app produces internally.

**Unblock by:** Marketing decision, separate workstream.

Answer:Not a concern at all. do not both about this

**Applied:** No action — out of spec scope (marketing workstream).

---

## OQ-10 — Email magic-link rate limiting

**Status:** Supabase has built-in rate limits but no spec-level number.

**Why it matters:** Abuse vector if not bounded.

**Proposal:** Use Supabase Auth's default magic-link rate limit (one per 60s per email) without override. Document in `07 §2.4`.

**Unblock by:** Engineering choice during implementation; no user decision needed.

Answer: Keep it that way

**Applied:** Documented in `07 §2.4` — Supabase default (one magic link per 60 s per email), no override.

---

## OQ-11 — Receipt thumbnail generation

**Status:** Not specced explicitly.

Receipts are stored full-size. Listing them requires loading or generating a thumbnail per row.

**Why it matters:** UI perf when expense detail loads 10 receipts.

**Proposal:** Generate thumbnails client-side on first display (Coil handles this for images; PDFs need a custom path — render page 1 to a JPEG via a platform helper). Cache via Coil's disk cache. No server-side thumbnail pipeline in v1.

**Unblock by:** Confirmation that client-side thumbnail generation is acceptable.

Answer: We will not store receipts online as is. Apply a compression first while keeping the quality decently good (over 80%). Second, I do not know which one is more efficient. I don't want receipts to be locally accessible to all users automatically (unless they open the receipt, which triggers a fetch for the compressed receipt from supabase storage. the reason behind this is to prevent the app from bloating. Cache should also be invalidated after 14 days. Reasoning: If I view a receipt today for Puerto rico, and then in a month, visit Chicago with my friends, my probability of checking a PR receipt while settling a Chicago debt is close to 0). Therefore, we want to reduce storage space waste. I don't know which side (for thumbnail generation) is more efficient, faster, and less bug prone. 

**Applied (D-22):** Client compresses to JPEG/HEIC ≥ 0.80 (long edge ≤ 2048 px) before upload; full image + thumbnail both uploaded; receipts never bulk-sync; full image fetched only on open; Coil disk cache has a **14-day TTL**. Spec: `02 §3.10/§8`, `03 §15`, `04 §2.3/§7`, `06 §5.9`, `07 §10.1`. ACs M2-014/015.

**Thumbnail decision (delegated to me):** **Generate the thumbnail client-side at upload time and store it as a separate Storage object** (`{receipt_id}_thumb.jpg`). Chosen over the two alternatives because:
- vs. *client-side generation on first display*: that approach must download the full image before it can make a thumbnail — defeating the "don't auto-download receipts" goal and making lists slow.
- vs. *server-side thumbnail pipeline*: that adds an Edge Function + image library on the server (more moving parts, more failure modes) for no benefit here.
Generating once on the device that already holds the original is the fastest at display (lists fetch only a few-KB thumb), lowest-bloat, and least bug-prone. PDFs render page 1 to a JPEG thumbnail via a platform helper (`06 §5.9`).

---

## OQ-12 — Group base currency change with historical expenses

**Status:** Not specced.

If a group changes `base_currency` after expenses are recorded, what happens to:

- "Approximate total" displays (just re-converts at the new base; fine).
- Trip Overview totals (same — display-time conversion).
- Multi-currency multi-expense settlement that defaults to USD in v1 (does it now default to the new base? D-05 says no, v1 is USD-fixed).

**Why it matters:** Confused UX if changing base seems to do something invisibly.

**Proposal:** Allow change; surface a small footnote on the group settings screen: "Base currency affects display totals only — your expenses stay in their original currencies." No re-computation; no migration.

**Unblock by:** Confirming the footnote-only approach.

Answer: Allow change; surface a small footnote on the group settings screen: "Base currency affects display totals only — your expenses stay in their original currencies.

**Applied:** Footnote added under the base-currency editor in `05 §9` (About). No re-denomination; documented in `04 update_group`. New AC-M5-070.

---

## OQ-13 — Receipt-only "expense"?

**Status:** Out of scope for v1.

Some real workflows want to attach a receipt with no expense yet ("I'll figure out the split tomorrow"). Currently every receipt belongs to an expense.

**Why it matters:** Friction in the moment.

**Proposal:** Drafts. v1 retains draft state in local Room only (the half-filled Add Expense screen) but does not persist drafts across reinstalls. v1.1 can add server-synced drafts.

**Unblock by:** No action needed in v1; this is documented as a v1.1 candidate.

Answer: I want server synced drafts to be part of v1

**Applied (D-24):** New `drafts` table (`02 §3.17`, owner-only RLS `§5.2a`); `receipts` relaxed to XOR(`expense_id`,`draft_id`) so a receipt can attach to a draft (receipt-first). Lifecycle in `03 §3.5` (auto-save, receipt-first, convert reuses draft id + re-points receipts, discard cascades, single-owner LWW). RPCs `save_draft`/`delete_draft`, drafts in pull + Realtime + offline queue (`04`). UX auto-save/resume/discard (`05 §5.5`), `DraftRepository` + use cases (`06`). ACs M2-070…074.

**Note on receipt storage path:** to support receipt-first cleanly, the Storage path was decoupled from the parent id (`groups/{group_id}/receipts/{receipt_id}.{ext}`) so a draft's receipts don't need to physically move when the draft converts to an expense.

---

## OQ-14 — Member nicknames

**Status:** Not specced.

Bob may want to call Tyler "Ty" in group A but "Tyler" in group B.

**Why it matters:** Most existing apps don't do this. The placeholder-merge flow already accommodates the "two names for the same person" reality.

**Proposal:** Out of scope for v1. Display names are global per user. Group-level nicknames are a v2 candidate.

**Unblock by:** No action.

---

## OQ-15 — Conflict-tab pre-fill: equal-residual?

**Status:** Spec says NO pre-fill (per App_Overview).

The conflict resolver enters Tyler's share manually; the system does not pre-fill. App_Overview is explicit on this.

A sensible default that the spec EXPLICITLY rejects: "what would you get if the existing split mode were applied as if Tyler had been there from the start?" The brief argues that any pre-fill biases the resolver and can hide a wrong number.

**Why it matters:** Spec rigor; documenting that this is a deliberate non-feature, not an oversight.

**Proposal:** Keep no pre-fill. Implementing agents MUST NOT add a "suggested share" or "what would the math say?" affordance even if it seems helpful.

**Unblock by:** No action — this is closed.

---

## Resolved / no longer open

The following were considered open during spec drafting and are now resolved:

- **Local DB choice:** Room KMP (D-16).
- **Auth methods:** Google, Apple, Facebook OAuth + email magic link (D-14).
- **Money rounding:** Integer subunit values; largest-remainder; deterministic by member-join order (D-17).
- **Storage caps:** 500 MB soft, 1 GB hard, per group (D-09).
- **Settled-tab sort:** by original expense date (D-10).
- **FX staleness signal threshold:** > 7 days (D-07).
- **Archive notifications:** silenced, like chat app archive (D-11).
- **First-launch FX failure:** silent (D-08).
- **Invite link lifecycle:** persistent + admin-rotatable (D-12).
- **Deep link fallback:** clipboard + toast (D-13).
- **Identity model for joiners:** account required + placeholder claim flow (D-01).
- **Multi-currency balance representation:** per-currency, never converted at rest (D-04).
- **Cross-currency multi-expense settlement:** USD in v1 (D-05).

---

**End of spec.**
