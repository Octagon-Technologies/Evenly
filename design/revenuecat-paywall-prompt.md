# RevenueCat paywall prompt — Evenly Pro

Paste the block below into RevenueCat's paywall builder. Attach `evenly-paywall-light.png` and
`evenly-paywall-dark.png` as the visual reference, and upload
`design/assets/paywall-feather-light.png` / `-dark.png` as the background image assets.

---

Build a mobile paywall for **Evenly**, an expense-splitting app. Offering `default`, entitlement `pro`,
two packages: `$rc_monthly` and `$rc_annual`. Annual is preselected. There is **no free trial and no
introductory offer** on either package, so no element may say "trial", "free week", or "try free".

**Type.** IBM Plex Sans throughout. Headline and prices SemiBold (600); body and list items Regular
(400/450). Tight negative tracking on the headline (about -0.038em) and prices (-0.035em); near-zero on
body text.

**Colour, light mode.** Page `#FFFFFF`. Primary text `#0B1220`. Secondary text `#5B6577`. Tertiary
`#9AA3B2`. Brand blue `#3762E3` for the button, the selected card border, the Pro badge and the savings
chip. Selected card fill `#F4F7FF`. Unselected card border `#E6EAF0`. Blue text (links, the per-month
line inside the selected card) `#2B55D6`.

**Colour, dark mode.** Page `#000000`. Primary text `#E7ECF3`. Secondary `#9BA6B8`. Tertiary `#67738A`.
Brand blue `#3A52D6`. Blue text `#7AA6FF`. Selected card fill `rgba(110,155,255,0.07)`. Card border
`rgba(110,155,255,0.16)`.

**Background image.** A feather watermark, anchored to the top-right and bleeding off the right edge,
roughly 70% of the screen width, top-aligned above the headline. Transparent PNG, already faded — do not
apply extra opacity. Use the light asset in light mode and the dark asset in dark mode.

**Layout, top to bottom, 22pt side padding:**

1. Close/back chevron, top-left, primary text colour.
2. Centred lockup: feather mark, the word "Evenly" at 33pt SemiBold, then a "Pro" badge — white text on
   brand blue, 14pt SemiBold, 9pt corner radius, 6x10pt padding.
3. Headline, centred, 30pt SemiBold, line height 1.13, two lines:
   **"Get the full power of Evenly"**
4. Feature list, left-aligned, 15pt, 15pt between rows, each row a thin checkmark glyph in primary text
   colour (no circle, no tinted chip) then the text:
   - Unlimited receipt scans
   - Works in all the groups you're in
   - Your subscription covers the entire group
   - Export every bill and settlement in CSV.
5. Flexible space.
6. **Two package cards side by side, equal width, 11pt gap.** Each card: a radio dot then the plan name
   on one row, the price below at 22pt SemiBold, then one small line of secondary text. Corner radius
   16pt.
   - Left, `$rc_monthly`, unselected: "Monthly" / price / "<annualised price> a year"
   - Right, `$rc_annual`, selected: "Yearly" / price / "<monthly equivalent> a month" in blue
   - Selected state is a 2pt brand-blue border plus the tinted fill; unselected is a 1.5pt neutral border
     on the page colour.
   - A **savings chip** sits on the top-right border of the annual card, overlapping it: brand-blue pill,
     white text, 10pt SemiBold, reading "Save {discount}%".
7. Primary button, full width, brand blue, white 16pt SemiBold text, 15pt corner radius:
   **"Continue with Evenly Pro"**
8. Directly under the button, centred, 11.5pt secondary text, the renewal disclosure. Bind it as
   `Renews automatically at {{ product.price }} every {{ product.period }} until cancelled.` The absolute
   renewal date is added by the app beneath this composable (see notes) because the editor has no variable
   for it.
9. Centred 12.5pt line: "Only need it for one trip?" followed by a blue link "Get a group pass".
10. Footer row, centred, 11pt tertiary text: Restore purchase, Terms, Privacy.

**Bind every price to package variables — never hardcode.** Each card's price is the package's localized
price; the annual card's sub-line is its per-month equivalent; the monthly card's sub-line is its
annualised price; the chip is the annual package's discount relative to monthly. At target US pricing
that renders $2.99 / $35.88 a year and $19.99 / $1.67 a month with a 44% chip, but every one of those must
come from the store product so other currencies and regions are correct.

---

## Notes for whoever pastes this

- **There is no renewal-date variable.** Verified against RevenueCat's variable reference: the only date
  the editor exposes is `product.offer_end_date`, which is the end of an *intro offer* and is empty for us
  since we run no trial. So the absolute date cannot come from the dashboard. Split the requirement:
  RevenueCat's line carries price and period (step 8), and `ProPaywallScreen` renders the absolute date in
  the Evenly-owned strip beneath the composable, computed as today plus the selected package's period.
- **Do not hardcode the savings percentage.** Bind the chip to `{{ product.relative_discount }}`. A typed
  number silently goes wrong the moment either price changes, and wrong in every non-US store immediately.
- **Check that the builder supports a custom font** before committing to IBM Plex Sans. If it only offers
  a fixed list, take the closest grotesque and tell me, and I will re-tune the sizes and tracking, because
  the layout is tight and a wider face will break the two-line headline.
- **Check per-theme background images.** If the builder only takes one image, use
  `paywall-feather-universal.png`, which is tuned to survive both grounds.
- **Step 9 may not be expressible.** PRO_PASS_SPEC.md §8.2 already anticipates this: if the builder cannot
  render a footer link that triggers an app action, drop step 9 from the RevenueCat paywall and render an
  Evenly-owned row directly beneath the `Paywall()` composable instead. Take the dashboard-side option if
  it exists, so the escape hatch stays remotely editable.
- **App Review requires** the price, the billing period, the auto-renew disclosure, Restore, Terms and
  Privacy all present on this screen. Steps 6 to 10 cover them; do not let a layout tidy-up remove one.
