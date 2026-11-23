# Bill dates and payments on the bill

**Status:** in progress (`feat/dates-and-payments`)
**Mockup:** `design/brainstorm/dates-and-payments.html` (revision 8, owner-reviewed 2026-11-22)

---

## 1. Behavior brief

**Problem.** Two things. (a) Nobody can set a bill's date, and every new bill was dated in UTC, so a bill
added just after midnight in Nairobi filed under the day before. A scanned receipt's printed date was
ignored. (b) A payment could only be recorded by the person who owed ("Settle this"), and payments sat in a
separate "Payments" section, so "edit the bill" and "edit what I paid" were easy to confuse. The payer could
not record "Purity paid me" at all.

**User outcome.** (a) Every bill shows its date as a small chip beside its title; a scan fills it from the
receipt; anyone editing can change it. (b) On a bill, each person's balance ("210 left ›") opens one sheet to
record a payment and see that person's history, for the payer on every row and for each person on their own.

**Non-goals.**
- No time of day is stored or shown for a bill. The printed time still only picks the suggested name.
- Batch receipt import (section C of the mockup) is a future feature, not built here.
- Payments stay visible to everyone in the group ("Open", owner call). No RLS change.
- A payment's currency is the bill's currency. No currency picker in the payment sheet.
- Settle up (pairwise, across bills) is unchanged.

**Platforms and systems affected.** Android and iOS (`commonMain`): `domain/expense`, `data/repository`
(settlements), `ui/screen/expense`, `ui/screen/bill`, `ui/navigation`. `supabase/functions/extract-receipt`
(new optional `printed_date` field). No schema change.

---

## 2. Acceptance criteria

**Dates**
- Given a new expense or bill, the date chip reads "Today" (the phone's local date, not UTC).
- Given a scan whose receipt prints a date within the last 60 days and not in the future, the chip shows it
  ("Yesterday", "Nov 20"); a date outside that window is ignored and the chip stays on today.
- Given someone picked a date by hand, a later scan or quick read never overwrites it.
- Tapping the chip opens "When was this?": Today, Yesterday and the day before as chips, then a month
  calendar. Future days can't be picked. Nothing changes until "Use …".
- The chip shows the year only outside the current year ("Dec 30, 2025").
- Editing an expense or a bill keeps its saved date unless changed.

**Review screen (scan)**
- The review screen shows the title with the date chip, and ends in two buttons: "Assign items" opens the
  assign screen, "Share link" opens the QR screen. Both save first, and Back from either lands on the bill.
- The editor's own "Save & continue" still goes to the chooser.

**Payments on the bill**
- The bill's payer sees a balance chip on every other person's row; anyone else sees one on their own row
  only; rows the viewer can't act on show plain text. A bill paid by someone outside the group has no chips.
- The chip opens "Kevin paid you" / "You paid Andrew": the amount (starts at what's left, editable), "Via"
  method and "On" date on one line, a collapsible "History" card of that person's payments on this bill, and
  "Record payment".
- Recording more than what's left is refused on tap with the reason. Recording 0 asks for an amount.
- The method defaults to the one this device used last.
- "Edit" in History opens the edit sheet: amount, method, date, the person's share and what's left after,
  "Save", and "Cancel payment" (second tap confirms). Cancelling is a soft delete (void).
- Editing only the amount keeps the payment's date.
- A fully paid row shows a green "✓ Paid ›" chip, which still opens the sheet so a payment can be fixed.

**States that are not the happy path.**

| State | Expected behavior |
| --- | --- |
| Receipt prints no date / an implausible one | Chip stays on today, nothing said |
| Payment over what's left | Refused on tap: "That's more than the KSh 210 left." |
| Concurrent payment already paid it down | Refused by the repository; sheet says to check History |
| Edit by someone not in the payment | Not offered ("Edit" only on payments you're a party to); server guard also refuses |
| Offline | Records locally and syncs like any settlement |

---

## 3. Constraints

- **Data model.** No new columns. `settlements.payment_app` is free text (new values `MPESA`, `CASH`, `BANK`,
  `OTHER` alongside the existing app names); `settlements.settled_at` already exists and can now be a past day
  (stored at local noon). `expenses.expense_date` already syncs and merges as its own field.
- **Compatibility.** Older apps ignore `printed_date`. An older app shows an unknown `payment_app` value
  as written.
- **Last method** is device-local (`SecureStorage`), not synced.

---

## 4. Test plan

| Criterion | Proof |
| --- | --- |
| Chip labels, receipt-date trust window | `commonTest/.../domain/expense/ExpenseDateTest.kt` |
| `printed_date` normalization | `supabase/functions/extract-receipt/contract.test.ts`, `summary.test.ts` |
| Record / edit / void payments | existing `iosTest` settlement repository tests, plus a manual run |
| Screens | iOS simulator run + `ux-firsttimer` pass |
