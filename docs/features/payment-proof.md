# Payment proof, and a web bill that shows everything

**Status:** in progress (`feat/payment-proof`). Server migration `payment_proofs` and `web-claim` applied to
the live project 2026-11-23; the app and site ship with the branch.
**Owner agent/session:** the `feat/payment-proof` session
**Mockup:** `design/brainstorm/payment-proof-and-web-parity.html` (owner-approved 2026-11-23, "Ask for one"
removed)

---

## 1. Behavior brief

**Problem.** Someone says "I paid you $24 for the Uber." The person paid has no way to check, short of asking
for a screenshot over text and recording it themselves. Separately, the web bill sheet showed only the split
and the change log: no receipt photo, no payments, no comments.

**User outcome.** Whoever records a payment can attach a screenshot, and the other person can see it in one
tap on the bill, in the payment's details, and on the web.

**Non-goals.**
- No "Ask for a screenshot" nudge (owner removed it from v1).
- One screenshot per payment, replaceable. No multi-page proof.
- No OCR or verification of the screenshot. It is evidence for a person, not for the app.
- Adding a receipt photo from the web stays app-only. The web reads receipts.
- No group-wide "payments" list on the web; payments show per bill.

**Platforms and systems affected.** Android + iOS (`commonMain`: `data/` entity, DAO, sync, upload queue;
`domain/` `PaymentProof`, `SettlementRecord.chainId`; `ui/` settle sheets, bill screen, viewer), `supabase/`
(`payment_proofs`, push trigger, `web_group_snapshot`, `web-claim` actions), `web/` (group bill sheet, pay
back sheet).

---

## 2. Acceptance criteria

**Recording**
- The pay sheets ("Settle up" for one bill, and settle-up with a person) show an "Add a screenshot" tile.
  It is optional; recording never waits on it.
- Picking a photo turns the tile into the thumbnail with a remove; nothing uploads until the payment is
  recorded. Leaving the sheet discards the staged copy.
- Once recorded, the screenshot uploads in the background through the receipt queue (survives the app
  closing, retries on reconnect).
- The Cash case: where a payment-method picker offers Cash, the tile is hidden for it. `main` has no Cash
  choice yet (the per-person bill sheet that adds it is separate work); that sheet leaves the tile out
  when Via is Cash.

**Seeing**
- The bill's Receipt card becomes "Receipt & payments" (or "Payment screenshots" with no receipt). Each
  screenshot is labelled "Who · amount on this bill".
- Each payment row on the bill and on settle-up shows the screenshot thumbnail; for the two people in the
  payment, a payment without one shows a dashed "+" and "No screenshot".
- A person's row in "who owes what" shows a paperclip when one of their payments here has a screenshot.
- Tapping a screenshot opens the in-app viewer captioned with the payment ("You paid Andrew $24.00 · Zelle",
  date and who recorded it). Only whoever added it may delete it.
- The other person in the payment gets a push when a screenshot is attached ("Maya added a screenshot of
  the $24.00 they paid you"). A replacement is silent.

**Web (group page bill sheet)**
- Shows receipt photos, payment screenshots, the payments on this bill (who, method, date, amount here),
  and comments, in the app's order. A guest can post a comment and add or replace the screenshot on a
  payment they are in.
- "Pay back" has the same tile; a pasted screenshot works on a laptop.

| State | Expected behavior |
| --- | --- |
| Offline when recording | Payment records locally; screenshot waits in the queue and uploads later |
| Upload still running | Thumbnail shows the local copy with a spinner |
| Payment edited | Screenshot stays (it hangs off the chain id `original_settlement_id ?? id`) |
| Payment voided | Its row disappears; the proof row stays, unreferenced |
| Web: payment lands, screenshot upload fails | "Payment recorded, but the screenshot did not upload. Open the bill to add it again." |
| Web: undecodable image (HEIC outside Safari) | Sent as is if JPEG/PNG/WebP, else "That image type does not work here." |
| Someone not in the payment | Cannot add (no "+"), cannot remove; `web-claim` returns 403 |

---

## 3. Constraints

- **Data model.** New synced table `payment_proofs`, same shape as `receipts`, server-first. Not a nullable
  `receipts.settlement_id`: installed apps assume every receipt has an expense. Bytes live in the `receipts`
  bucket at `<group>/payments/<chain>/<id>.<ext>`, so the bucket's policies and the group purge's prefix
  delete already cover them. The outbox (`receipt_uploads`, device-local) gained a nullable `settlement_id`.
- **One live proof per chain** is enforced by the writers (app and `web-claim`), not a constraint: the old
  row's tombstone and the new row can sync in either order.
- **Who may attach** is enforced in the app and in `web-claim`, not in RLS (membership-scoped like
  receipts). A server guard on insert would reject a proof that syncs before its payment row.
- **Privacy.** Same exposure as receipts: the bucket is public (`supabase/AGENTS.md` Storage). A payment
  screenshot can show account details; it is visible to the group only by URL obscurity, like a receipt.
- **Realtime.** Not published; the doorbell triggers cover it.

---

## 4. Test plan

| Criterion | Proof |
| --- | --- |
| Proof follows an edited payment; snapshot carries proof, per-bill amounts, receipts, comments | `supabase/tests/payment_proofs.sql` |
| Newest live proof per chain; remove is a soft delete | `iosTest/.../ActivityRepositoryTest.kt` |
| Queue query returns only proof uploads | `iosTest/.../ReceiptUploadDaoTest.kt` |
| Chain id | `commonTest/.../SettlementChainIdTest.kt` |
| Synced + wiped table lists agree | `SyncedTablesTest`, `EntitySerializablePartitionTest` |
| Screens | iOS simulator run; web `?mock` walkthrough (Airport Uber has a proof, Groceries pay-back) |

---

## 5. What shipped

- Server and `web-claim` live (2026-11-23). App and site on `feat/payment-proof`.
- Not built: the per-person bill payment sheet ("Kevin paid you" with Via/On chips) is uncommitted work in
  another session; when it lands, drop `PaymentProofTile` into it above "Record payment" and hide it when
  Via is Cash.
