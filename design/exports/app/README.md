# Real app screenshots

Captured from the iOS simulator on 2026-08-18. These are genuine app screens, not drawings —
`web/public/app/` holds the downscaled copies the site serves.

| File | Screen | Where it is used |
| --- | --- | --- |
| `expenses-tab.png` | `GroupExpensesTab`, Lamu Trip | `/waitlist` hero phone |
| `balances-tab.png` | `GroupBalancesTab`, Lamu Trip | captured, not placed yet |

## Reproducing the data

The seed group was soft-deleted after capture, so recapturing means seeding again. This recipe is
also the thing the site copy has to agree with.

**Group** Lamu Trip, palm emoji, USD. **Members** Bob (you), Maya, Diego, Sam.

| Expense | Paid by | Amount |
| --- | --- | --- |
| Tamarind Dhow (itemized) | you | $77.20 |
| Boat to Manda | Maya | $45.00 |
| Chandarana Market | you | $63.40 |
| Petley's Bar | Sam | $54.00 |
| Uber to Diani | Maya | $32.80 |
| Lamu Beach House | Diego | $420.00 |

Tamarind Dhow is a Restaurant bill holding two items, Grilled snapper $42.00 claimed by you and
Coconut rice $14.50 claimed by Maya, plus $20.70 tax. Resulting balances: you owe Diego $89.15,
Maya owes you $16.21, Sam owes you $2.35.

## Two things that cost time

- **The amount field takes whole units, not cents.** Typing `42000` for $420.00 gives $42,000.
- **The form does not clear on back.** The back arrow returns to the split-type chooser but keeps
  every field, so the next entry appends to the last one. Leave with Save, or close with the X.

Capture with `xcrun simctl io booted screenshot --type=png <path>` rather than the MCP screenshot
action, which hands back a scaled image rather than a file.
