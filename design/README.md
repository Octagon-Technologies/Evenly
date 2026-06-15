# ShareCost — Design reference

This folder holds the finished mobile UI exported from **Claude Design**, plus its decoded
source. It is the **visual source of truth** for the Compose Multiplatform UI built under
`code/shared/src/commonMain/.../ui/`. When a screen's look and the prose spec (`../spec/`)
disagree, **the design here wins** (per the build decision).

## Contents

| Path | What |
|---|---|
| `ShareCost-Standalone.html` | The original self-contained export (open in a browser to see the live, interactive design). |
| `extract.py` | Decoder: unpacks the HTML's `__bundler/manifest` (gzip+base64 modules) and `__bundler/template` (the CSS) into `src/`. Re-run with `python3 extract.py`. |
| `src/design.css` | **The complete design system**: `@font-face` (IBM Plex), `:root` tokens, and every `.sc-*` component class with exact px/weights/colors. The definitive styling reference. |
| `src/components.jsx` | Shared UI primitives (the React originals of our `ui/components/`). |
| `src/icons.jsx` | The 70-icon line set (name → 24×24 SVG path), stroke 1.6, round caps. |
| `src/overview.jsx` | Design principles + palette swatches (mirror in the component gallery). |
| `src/screens-*.jsx` | The 22 screens (see map below). |
| `src/canvas.jsx` | How the design composes all artboards (reference only). |

## Design language (from `overview.jsx` + `design.css`)

- **Blue-led monochrome, light-first.** One chroma — blue `#2563EB` — expressed by weight. White
  page, hairline borders over shadows. Principle: **"calm blue for success — never green."**
- **IBM Plex Sans** for UI, **IBM Plex Mono** (tabular figures) for *all* amounts.
- Signature: every amount is a large mono **remaining** over a small muted strikethrough **original**.
- Balances shown as plain pairs, **never netted**.

### Tokens (`:root`)
| Token | Light | Token | Light |
|---|---|---|---|
| `--blue` | `#2563EB` | `--page` | `#FFFFFF` |
| `--blue-press` | `#1E40AF` | `--surface` | `#F6F8FB` |
| `--blue-tint` | `#EFF4FF` | `--border` | `#E6EAF0` |
| `--blue-tint-2` | `#DBE6FF` | `--border-strong` | `#D6DCE6` |
| `--ink` | `#0B1220` | `--red` / `--red-tint` | `#E0686B` / `#FDEEED` |
| `--ink-2` | `#5B6577` | `--amber` / `--amber-tint` | `#687590` / `#EEF1F8` (a muted slate, not yellow) |
| `--ink-3` | `#9AA3B2` | radii | card 16 · sm 10 · pill 999 (btn 14 · input 12 · chip 8) |

> The dark theme is **derived** for the app (the export ships light only) — see `ui/theme/Color.kt`.
> The bundled fonts are subset woff2 keyed by UUID; the app loads the full IBM Plex TTFs in
> `code/shared/src/commonMain/composeResources/font/` instead.

## The 22 screens → source file

| # | Screen | File | App route |
|---|---|---|---|
| 1 | Sign in | `screens-auth.jsx` | `SignIn` |
| 2 | Email / magic link | `screens-auth.jsx` | `MagicLink` |
| 3 | Onboarding (5 steps) | `screens-auth.jsx` | `Onboarding` |
| 4 | Home / group list | `screens-home.jsx` | `Home` |
| 5 | New group (sheet) | `screens-home.jsx` | `NewGroup` |
| 6 | Group · Expenses tab | `screens-group.jsx` | `GroupHome(tab=Expenses)` |
| 7 | Filter (sheet) | `screens-group2.jsx` | `Filter` |
| 8 | Search overlay | `screens-group2.jsx` | `Search` |
| 9 | Group · Balances tab | `screens-group.jsx` | `GroupHome(tab=Balances)` |
| 10 | Conflicts tab + include sheet | `screens-group2.jsx` | `GroupHome(tab=Conflicts)`, `IncludeMember` |
| 11 | Trip overview tab | `screens-group2.jsx` | `GroupHome(tab=Overview)` |
| 12 | Expense detail | `screens-expense.jsx` | `ExpenseDetail` |
| 13 | Add / edit expense | `screens-addexpense.jsx` | `AddExpense` |
| 14 | Settle one expense (sheet) | `screens-settle.jsx` | `SettleExpense` |
| 15 | Settle a person (2 steps) | `screens-settle.jsx` | `SettlePerson` |
| 16 | Deep-link confirm (sheet) | `screens-settle.jsx` | `SettleConfirm` |
| 17 | Group settings | `screens-settings.jsx` | `GroupSettings` |
| 18 | Profile & settings | `screens-settings.jsx` | `Profile` |
| 19 | Archived groups | `screens-home.jsx` | `Archived` |
| 20 | Join group (sheet) | `screens-home.jsx` | `Join` |
| 21 | Reconcile + confirm | `screens-misc.jsx` | `Reconcile` |
| 22 | State kit (offline/skeleton/error/empty) | `screens-misc.jsx` | folded into shared state components |
