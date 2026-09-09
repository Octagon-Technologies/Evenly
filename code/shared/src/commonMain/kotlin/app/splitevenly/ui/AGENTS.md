# `ui/` — Compose screens, navigation, copy

Governs `ui/**` in `commonMain` plus the `ui/theme/SystemBars.*` actuals. This is the largest layer in the
app (~15.7k lines across 77 files, verified 2026-07-23), and the one where a mistake is visible to a real
person. Money math lives in `../domain/AGENTS.md`; persistence in `../data/AGENTS.md`.

**`design/` is the visual source of truth.** The Claude-Design export is blue/light and intentionally
**overrides** the older spec's dark/green. Do not "fix" the UI back toward the spec.

## Before you write Compose

1. **Mock it first.** For any new screen or non-trivial UI change, produce a faithful visual mockup for the
   owner to react to *before* writing Kotlin. Naming, controls, density, and flow get settled in the mock.
   This has repeatedly surfaced simplifications that would have been expensive to discover in code.
2. **Run the `ux-firsttimer` skill in build-time mode before calling it done.** Walk the flow cold as the
   relevant persona (usually Sam the invited friend or Diego the dinner claimer — the low-patience ones) and
   clear every P0/P1. The author's curse of knowledge makes everything feel intuitive; it usually isn't.
   "Compiles and runs" is necessary, not sufficient. The bar is **a confused friend could do this unaided.**
3. **Never leave a silent dead end.** Keep action buttons live and validate on tap, showing what's missing
   (hint plus tap-to-reveal). Do not grey out a control with no cue explaining why.

## Screens stay DI-free — Route wrappers do the wiring

Screens take plain callbacks so `@Preview` works. The **Route wrapper** in `ui/navigation/` is what
`koinInject`s the repositories and binds the callbacks. Never `koinInject` inside a screen Composable, and
never put `deleted_at`, conflict resolution, or any other data-safety logic in a Composable or a wrapper —
that belongs in the repository layer.

## Navigation

**Tabs are screen *state*, not routes.** Both the app-root nav (`ui/navigation/MainShell.kt`, rendered at
`Route.Home`) and the in-group nav (`GroupHomeScreen`) keep their tabs as a `remember`ed enum inside a single
destination. Two reasons, both load-bearing: Back leaves the section instead of cycling tabs, and there is no
enum nav-arg to crash the Native NavHost.

**A type-safe nav `Route` arg that is an enum crashes the NavHost on Kotlin/Native.** Use `String` — the tab
arg does. This is the single most repeated iOS-only crash in this repo.

`MainShell` is the root bottom nav: **Groups** + **Settings**, where Settings *is* `ProfileScreen`. Opening a
group is a full-screen push *over* the shell. There is **no standalone `Route.Profile`** — reach the profile
via the Settings tab, not a Home avatar.

**A push disposes the destination underneath, so `remember` in a Route wrapper does not survive Back.**
Anything a wrapper must keep across a push (a one-shot gate's answer, a completed check) belongs in a
ViewModel scoped to that back-stack entry, which lives until the entry is popped. `HomeGateRoute`'s
pending-deletion check held its result in `remember` and so re-ran on *every* Back into Home, blanking the
screen behind a network call each time — the window background reads as black on iOS. The related rule:
**a route wrapper must never render nothing while it waits.** Draw the `page` color, or the window shows
through.

## System bars blend via the theme, edge-to-edge

`StatusBarScrim` (in `EvBars.kt`) paints the `page` color behind the status bar, and
`EvBottomNav(navBarInset = true)` paints `page` behind the nav bar. **Each chrome owns its own inset** — do
not wrap a whole screen in `systemBarsPadding()` when it uses the scrim, or you double-pad. Status-bar *icon*
contrast is driven from the active theme (light/dark), never from the OS setting and never with
per-platform color code.

## Brand assets have exactly one source

The Evenly feather is a traced outline that lives **only** in `components/EvWordmark.kt`
(`EvenlyMark` + `EvWordmark` + `EvWordmarkStacked`). The Android launcher/splash vector drawables and
the iOS icon PNGs are *generated from that same outline* — if the mark ever changes, regenerate them
rather than hand-editing one and letting the two drift.

`screen/SplashScreen.kt` holds `EvenlySplashBlue`, which is deliberately **not** a theme token: the
Android `windowSplashScreenBackground` and the iOS `UILaunchScreen` colour are baked into resources
that cannot read the Compose theme, so all three must be edited together. White-on-blue is why the
splash needs no dark-mode variant. See root `AGENTS.md` §4.8.

Components use the **`Ev` prefix** (`EvButton`, `EvChip`, `EvDragSheet`). The old `Sc` prefix was
ShareCost-era and is fully retired; do not reintroduce it.

## Design tokens that bite

- **Use `c.blueText` for foreground blue text and icons.** `c.blue` is fill-only — it blends into the page in
  dark mode and the text disappears.
- **Owe/owed colors are deliberate, not a bug:** amber for "you owe", green for "you're owed / settled". The
  palette intentionally departs from the "never green" principle *only* for settle-up states. Audit for
  consistency; do not revert these to blue.
- **Sheets:** `EvDragSheet` is the no-dim, draggable sheet; `EvSheetScaffold` is the dimmed one. Pick by
  whether the content behind should stay legible.

## Copy rules

**User-facing copy is concise and functional, never explanatory marketing.** A subtitle or caption earns its
place only if it tells the user something they'd otherwise be confused about — a constraint, a warning, or
what happens next ("It uploads after you save."). Cut copy that describes or sells the feature. Both of these
were removed for describing the *design* rather than anything the user needed to act on:

> ~~"Pick once — you'll get a focused editor."~~ ~~"Balances are shown per person and never simplified
> across the group."~~

When unsure whether a line earns its place, ask: does removing it leave the user unable to *do* something, or
just unable to appreciate the reasoning? If the latter, cut it.

**Never use em dashes (—) in user-facing strings.** Hard rule, no exceptions. Rewrite with a period, comma,
or parentheses ("It uploads after you save." not "It uploads — after you save."). Scope is Compose
`Text`/caption/subtitle strings actually rendered to users — **not** code comments, KDoc, or commit messages.
This is enforced by the `em-dash-guard` hook, which only inspects Kotlin string literals. If a dash is
genuinely required (a rendered document, a test fixture), add the marker `agents-allow-dash` in a comment on
that line.

**Avoid jargon.** Grep new user-facing strings against the `ux-firsttimer` skill's jargon blocklist before
finishing.

## Feature-specific screen rules

**"Who had what?" (`BillClaimScreen`) is assignment-first.** The person holding the phone taps who had each
item, **for anyone — app user or not** (proxy assignment writes claims and portions on their behalf), so a
bill works when only one person in the group has the app. Simple items are tap-chips (the same
`EvParticipantChip` as the participant picker); a multi-count line with mixed amounts opens the portions
builder. The old per-line "Ask the group" jump and the per-current-user "your tab" claim UI are **retired** —
do not reintroduce either.

**The payer's three web-claim screens hang off the claim screen** (`BillReviewEditsScreen`,
`BillClaimProgressScreen`, `BillShareLinkScreen`; wired in `ui/navigation/WebClaimRoutes.kt`). Their
entry points are on `BillClaimScreen` and nowhere else: the phone-holder is already there while the
table is claiming, and that is the only moment any of them is wanted. Three rules that look like taste
and are not (`WEB_CLAIM_SPEC.md` §2.7, §2.9, §3.9):

- **"What changed" reports; it does not adjudicate.** Every change on that screen has already applied
  (`WEB_CLAIM_PATCH_PLAN.md`). Editing a line moves the bill total and therefore everyone's money, so
  the payer is *told*; joining a claim moves two people's with both at the table, so nothing is said.
  That asymmetry is the announcement, not a wait. Do not harmonise the two, and do not re-add an
  approval gate. **No "undo all"** either, and no rights hierarchy: anyone on the bill may undo, an undo
  is itself an attributed entry, and the log is the tiebreak. The banner clears by being **read** (a
  device-local marker in `SecureStorage`, `changesSeenKey` in `WebClaimRoutes.kt`), not by deciding.
  **A notification that clears itself needs a durable door beside it** — the "What changed" row in
  `WebClaimActions` is that door. Without it, reading the banner once puts the record of who changed
  what, and the undo with it, permanently out of reach. Found by walking the screen, not by reading it.
- **The bill QR and the group invite are different links and stay apart.** Per-expense, 72 hours,
  no account vs. permanent and account-required. The QR leads; the invite is the tertiary text button.
  The scope reassurance under it ("this bill only") is not decoration: the payer is deciding whether to
  pass a URL into their finances round a table.
- **"Hasn't opened the link" is only said about someone without the app.** An app member who simply
  hasn't claimed gets different wording, because telling the payer to re-send a link to somebody who
  was never going to use one turns a nudge into noise. Same reason the freshness line degrades from
  "updated just now" rather than lying.

**The participant picker scales with group size.** ≤6 members render as inline select-all/deselect toggle
chips; larger groups collapse to a summary row that opens a searchable member-picker sheet. Same underlying
selection state either way.

**Group home surfaces unresolved bills.** The "claim your items" card and the **Unresolved bills** section
are fed by `BillRepository.observeUnresolvedBills`; tapping one opens the live claim screen
(`Route.ClaimBill`). A bill is unresolved when a line still needs someone *or* a participant hasn't marked
done — **claiming is not paying.**

**The bill editor takes either price.** Type the per-unit ("each") *or* the whole-line ("total of N") — the
other derives and the last-touched field is truth (`EditBillItemUi.driver`), so a receipt's line total goes
in without dividing by hand. The extras card is deliberately lean: no per-line split choosers, and the
discount reads as a deduction (leading "−" plus caption), not a plain positive.

**Balances tab** is two sections (**You owe** / **Owes you**), each row expandable to its per-expense
breakdown, fed by `ExpenseRepository.observeOutstandingItems` (derived remaining, per-expense currency). The
settle screen lists those same lines as checkable targets, and highlights the member's `preferredPaymentApp`
(the synced `users.preferred_payment_app` column, set in the Payment-apps editor) as the default way to pay
them.

**Receipt scans are unlimited and free.** Every scan entry point goes directly to source selection and
the OCR request. There is no local allowance, meter, upgrade prompt, or paid fallback. A server 429 is
still a short abuse-protection cooldown and must be explained as a retry-later state, never as a quota.

**The camera goes through `CameraPermissionGate`, never straight to the picker.** `rememberCameraPermissionGate()`
at the top of a route wrapper, every camera-capable source handler passed through `gate.wrap { ... }`, and
`CameraPermissionGateHost(gate)` **last** in the function (an overlay emitted after the screen draws
underneath it). Only `PickSource.Camera` is intercepted. The sheet is
deliberately **undimmed**: the scan card stays legible behind it, so it reads as part of the screen rather
than a trap door. Both halves of the drawn system dialog are live and do what they depict, because a drawing
of a button that swallows taps is the dead end the gate exists to remove. Four entry points across
`LedgerRoutes`/`BillRoutes` share it; a fifth that skips it is an iOS-only grey-viewfinder bug that Android
testing cannot surface. See `../platform/AGENTS.md`.

**The scan card carries its three sources** (`ItemizedExpenseBody`). Tapping it used to open
`ScanSourceSheet` and only then the OS picker, which is two taps and a sheet between "scan this" and a
camera. That sheet still exists for the failed-scan re-pick path; the card does not use it.

**Export lives in Group settings and nowhere else.** The Overview tab used to carry an "Export as
image" button wired to an empty lambda and an "Export" chip in its app bar that `EvChip` gives no
`onClick` at all, so neither could ever do anything. Both are gone. If export returns to Overview it
ships with a working callback, or it does not ship.

**Export is always free.** Group settings invokes the CSV export directly after membership checks; it
must never branch to a tier gate or upgrade prompt.

**Receipts are viewed *in-app*, never handed to an external browser.** Tapping a receipt opens the
full-screen `ReceiptViewerScreen` — a `HorizontalPager` over the expense's receipts with a bottom thumbnail
filmstrip; images pinch-to-zoom and PDFs render natively via the `PdfRasterizer` platform abstraction
(pages rasterize lazily, only when a PDF is actually opened, never for the filmstrip). The old
`UrlOpener.open(receipt.url)` hand-off is **gone** — do not reintroduce external receipt opening.

## Size ceilings

**600 lines per file.** New surface goes in a new file.

**Known offenders, quarantined** (counts re-verified 2026-08-25 with `wc -l`; every one of them had
drifted since the last check, which is the usual way a ceiling stops being one):

| File                                        | Lines |
| ------------------------------------------- | ----- |
| `ui/screen/expense/ExpenseDetailScreen.kt`  | 1227  |
| `ui/navigation/LedgerRoutes.kt`             | 929   |
| `ui/screen/bill/BillEditScreen.kt`          | 813   |
| `ui/screen/auth/WelcomeScreen.kt`           | 799   |

**The add-expense editor is split by feature** (2026-08-02, was 1116 lines): `AddExpenseScreen.kt` keeps
the shell — state, the shared header, the picker sheets. `SplitApproachChooser.kt` is the up-front
divide-vs-itemize question; `DivideSplitBody.kt` holds `DivideSplitState` plus the amount/method/preview
body; `bill/ItemizedExpenseBody.kt` holds `ItemizedBillState` plus the scan hero, item rows and extras,
next to the `ItemEditorRow`/`ExtrasCard` it already borrowed. Each body owns its own state class and takes
the participant `ids` as an argument, so selection stays owned by the screen. Both bodies emit their rows
straight into the caller's `Column`, so the caller's `spacedBy` still spaces them. **`BillEditScreen` still
has its own copy of the item-list body** — unifying the two was deliberately out of scope, not overlooked.
The scan sheets (progress, error, source row) came out to `bill/BillScanSheets.kt` on the way through,
which took `BillEditScreen` from 916 to 813 despite the scan-gate additions.

**The bill editor owns the payer and the roster too** (2026-08-08): "Paid by" (`bill/BillPayerRow.kt`,
reusing the add-expense `PayerSheet`) sits *below* the people, same order as the add-expense editor, and
the roster moved to `bill/BillPeopleSection.kt`. **A sheet must be rendered outside the editor's scrolling
`Column`** — inside it, the sheet's own scroller is measured with an unbounded height and Compose throws
on Native. That is why both files export a row and its sheet separately instead of one self-contained
component. Taking someone off the bill discards their claims, so that case confirms first; changing the
payer never does.

The rule for the offenders above is **do not grow them.** When you make a substantial change inside one,
extract the section you touched on your way out. Do not propose a big speculative rewrite — report the delta, not the
backlog. (`data/repository/BillRepositoryImpl.kt` at 804 is the one offender outside this layer.) Counts
verified 2026-08-03; the earlier figures for `LedgerRoutes` (580) and `BillRepositoryImpl` (636) had
gone stale, which is the usual way a ceiling stops being one.

**`BillClaimScreen.kt` sits at 598** and is one addition away from the ceiling. Step 6's entry surface
was extracted to `bill/BillWebClaimEntry.kt` on the way in for exactly that reason; the money side went
to `data/repository/BillPendingEdits.kt`. The next thing this screen grows should take the servings
sheet or the how-to guide out with it.
