# Web ↔ app parity audit

**Rule (web/AGENTS.md, 2026-11-24):** every screen that exists in both places copies the app. This file is the
screen map and the gap list. Update it when either side changes a screen.

Audited 2026-11-24 at 393px (iPhone base width) against the Compose source in
`code/shared/src/commonMain/kotlin/app/splitevenly/ui/`. Guests are mostly on iPhone, so the app's **iOS**
branch is the reference wherever the Compose code forks on `isIOS()` (buttons, sheets).

## Screen map

| Web (`web/src/…`) | App (`ui/…`) | Notes |
| --- | --- | --- |
| `marketing/Join.svelte` (invite landing) | `screen/home/JoinGroupSheet.kt` | App shows it as a sheet over Home |
| `screens/join/JoinPick.svelte`, `screens/PickName.svelte` | `screen/group/IdentityClaimCard.kt`, `IdentityChecklist.kt` | "Are any of these you?" |
| `screens/join/JoinName.svelte`, `screens/NameEntry.svelte` | none (app users have an account) | Styled with the app's field + button |
| `screens/group/GroupPage.svelte` | `screen/group/GroupExpensesTab.kt` + `GroupBalancesTab.kt` | App splits these into tabs |
| `screens/group/ExpenseSheet.svelte` | `screen/expense/ExpenseDetailScreen.kt` | App: full screen |
| `screens/group/ExpenseForm.svelte` | `screen/expense/AddExpenseScreen.kt`, `DivideSplitBody.kt` | |
| `screens/group/SettleSheet.svelte` | `screen/settle/SettleSingleSheet.kt` | |
| `screens/ClaimList.svelte` | `screen/bill/BillAssignScreen.kt`, `BillAssignCard.kt` | Rebuilt 2026-11-24 |
| `screens/ShareSheet.svelte`, `JoinSheet.svelte` | `screen/bill/BillServingsSheet.kt`, `PeoplePickerSheet.kt` | |
| `screens/AddItemSheet.svelte` | `screen/bill/ItemizedExpenseBody.kt` (item editor sheet) | |
| `screens/Summary.svelte`, `MyPayment.svelte`, `PayerSheet.svelte` | `screen/bill/BillReviewScreen.kt`, `screen/expense/BillPaymentSheets.kt` | |
| `components/Sheet.svelte` | `components/EvContainers.kt` `EvSheetSurface`, `EvSheetChrome.kt` | |

## Foundations (affect every screen)

| # | Gap | Web today | App | Severity |
| --- | --- | --- | --- | --- |
| F1 | Typeface | `IBM Plex Sans` declared but **never shipped**, so iPhones render SF Pro; money in SF Mono | Plex Sans everywhere, money in Plex Mono | P0: the single biggest "this is a different product" cue |
| F2 | Buttons | 48px tall, 14px corners, 15.5px label | iOS: 50px capsule (radius = height/2), 17px semibold; small 36px, 15px | P1 |
| F3 | Sheets | Full-bleed, 24px top corners, 38×4 handle, left title | iOS: floating card inset 8px, 32px corners, 1px border, 36×5 handle, centred 17px bold title in a 52px row, centred sub | P1 |
| F4 | Text fields | 1.5px border, 14px corners, 3px blue halo on focus | 1px `borderStrong`, 12px corners, 2px blue ring on focus, 16px text | P2 |
| F5 | Field labels | 12.5px weight 660 | 13px semibold `ink2` | P2 |
| F6 | Section labels | various (11.5px eyebrow, 12px uppercase) | 13px semibold, 0.6 letter-spacing, `ink3`, uppercase | P2 |
| F7 | Chips | 25px pills, 11.5px | Participant chip 38px pill, 14px semibold, Xs avatar 22px; status chip 28px, 8px radius, 13px | P1 |
| F8 | Avatars | 18px | Xs 22 / Sm 28 / Md 36 | P2 |
| F9 | Segmented control | ad-hoc | 3px inset surface track, 12px radius, 38px options, 9px radius, white selected with shadow | P2 |
| F10 | Colour tokens | Match the app's light scheme (`#2563EB` etc.) | Same | ✓ none |
| F11 | Dark mode | Light only | Light + dark | Deferred; out of scope for this pass |

## Group page (`/j/` after joining)

| # | Gap | Web today | App | Severity |
| --- | --- | --- | --- | --- |
| G1 | Structure | One long page: balance hero, waiting card, All/Mine, list | Bottom nav: **Expenses** · **Balances**; each its own page with a top bar | P1 |
| G2 | Header | Emoji tile + name + "You're Purity · Not you?" | Top bar: emoji + group name (title style) | P2 (keep "Not you?" as the subtitle; web-only need) |
| G3 | Filter | All / Mine | Active / All / Settled segmented, settled items in a collapsible "Settled (N)" tray | P1 |
| G4 | Day headers | 11px grey caps on white | Full-width `surface` band, 13px semibold `ink2`, sentence case | P2 |
| G5 | Expense row | Title + "X paid", amount or stake, text "›" | 40px category tile, title 15 semibold, Xs payer avatar + "X paid · $amount", stake label 11px over 15px mono (green when owed to you, ink when you owe), chevron icon | P1 |
| G6 | "Waiting on you" | Blue-tint card with "Claim ›" links | `UnresolvedBillsSection`: collapsible section above the feed | P2 |
| G7 | Add button | "+ Add expense" pill bottom-right | `EvFab`: 48px, 16px radius, 15px label, plus icon | P2 |
| G8 | Balances | "You owe / You're owed" list inside the hero | `GroupBalancesTab`: "YOU OWE" / "OWES YOU" sections, one card per person (Sm avatar, name, "N expenses · tap to see", Owe/Owed amount chip, chevron), expands to the expense lines and "Settle up with X" | P1 |

## Expense detail

| # | Gap | Web today | App | Severity |
| --- | --- | --- | --- | --- |
| E1 | Container | Bottom sheet | Full screen with a back top bar | P1 |
| E2 | Hero | "US$93.60 · Mary paid · 21 Nov" line | Card: hero amount, "total expense", personal status band, divider, payer avatar row with date, "Edit expense" secondary button | P1 |
| E3 | Split list | Name, amount, "US$23.40 left" | "SPLIT" section card; your row tinted `selectionTint`; payer row "Paid the bill" + share; debtor rows with remaining + progress | P1 |
| E4 | Actions | Two stacked buttons | Pay action inline on your own row; Edit is the secondary button in the hero | P2 |

## Add / edit expense

| # | Gap | Web today | App | Severity |
| --- | --- | --- | --- | --- |
| A1 | Amount | Plain text field | Card with a large mono amount input and the currency chip | P1 |
| A2 | Who paid | Native `<select>` | "Paid by" row: avatar, name, "Change" → picker sheet | P1 |
| A3 | Who's splitting | Native checkboxes in a list | Participant chips (38px pills with Xs avatar and a check) + "Add" | P0: the most "web form" thing on the site |
| A4 | Split modes | Evenly / Exact amounts | Same labels via the segmented control, per-person rows (Sm avatar, name, amount) | P2 |
| A5 | Container | Bottom sheet | Full screen "New expense" with a close top bar and a docked Save bar | P1 |

## Claim flow (`/b/`)

| # | Gap | Web today | App | Severity |
| --- | --- | --- | --- | --- |
| C1 | Claim list | — | — | ✓ rebuilt as the app's assign cards |
| C2 | "Who had it" sheet | Small 34px chips, ✕ to remove a typed name | Participant chips 38px + "Add person"; summary line under them | P1 |
| C3 | "Add me" sheet | Prose + chips | Same sheet pattern as C2 | P1 |
| C4 | Add item sheet | Stacked fields | App item editor: name, quantity stepper, price | P2 |
| C5 | Pick your name | Rows with "That's me" pills | Identity cards: avatar, name, evidence, full-width button | P2 |
| C6 | Summary / payment | Blue hero, cards | Bill review: section cards, mono totals, app buttons | P2 |

## Kept different on purpose

- No bottom-nav Overview tab, settings, members sheet or profile: those need an account.
- "Not you? Use a different name" stays on every screen (no account to sign out of).
- The install line stays a one-line footnote (§2.8).
- Light only, until dark mode is done for both surfaces together.

## Status after the 2026-11-24 rebuild

Done: F1–F10 (fonts shipped, `app.css` buttons/sheets/fields/labels/chips/avatars/segmented/check
match the app's iOS sizes; `$` symbol formatting like the app), G1–G8 (bottom bar with Expenses and
Balances, Active/All/Settled, day bands, app expense cards with category tiles, "Needs your pick",
FAB, balance cards that open to their expenses), E1–E4 (expense detail is a pushed screen with the hero
card and split list), A1–A5 (add/edit is a pushed screen: scan card, Title, large amount card, Paid by
row + picker sheet, people chips, segmented split, per-person rows, docked Save), C1–C3, C5 (avatars on
"Are any of these you?"), C6 (white hero card, primary "I paid").

Still open:
- **C4** add-item sheet keeps plain quantity/price fields; the app's item editor has a stepper.
- **F11** dark mode (both surfaces together).
- The invite landing (`marketing/Join.svelte`) is styled with the new tokens but is not modelled on
  `JoinGroupSheet.kt`.
- Category tiles use the built-in categories; a group's custom categories fall back to Other.
