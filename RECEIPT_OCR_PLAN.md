# Receipt OCR — the 2026-08-08 defects, and what remains

One receipt produced two defects with one cause: **we asked the model to do arithmetic.**

## The governing rule

> **The model transcribes. Code computes. Nothing in the extraction pipeline may ask a model to add,
> subtract, rescale, or reconcile a number.**
>
> And its corollary, which is the part that actually bites: **a validation gate whose two sides both come
> from the model cannot fail.** Every check must compare our arithmetic against something the model only
> copied, or against ink it cannot revise.

Where this lives in code: `contract.ts` (schema + prompt, transcription only), `amount.ts` (printed text →
subunits), `billMath.ts` (every sum and comparison), `index.ts` (the gates and the verify turn).

## The evidence

A Tom's Watch Bar receipt, scanned once. Printed versus what the app filled in:

| Line                       | Printed    | App        |
| -------------------------- | ---------- | ---------- |
| Bacon Avocado Burger       | 22.74      | 22.00      |
| 3 Strawberry Mint Sparkler | 38.22      | 38.00      |
| Pineapple Ginger Fizz      | 12.99      | 12.00      |
| Adobo Chicken Tacos        | 19.49      | 19.00      |
| Loaded Fries               | 14.24      | 14.00      |
| Srv Fee (4%) → gratuity    | 3.74       | 3.00       |
| Discount                   | −14.24     | −14.00     |
| Tax                        | 6.32       | **0.00**   |
| **Total**                  | **103.50** | **94.00**  |

`receipt_scan_log` for that scan (`95d1d72a…`, 21:29:45 UTC):

```
outcome: "ok"   tiers_used: ["primary"]   cost: $0.024   duration: 5.6s
```

`outcome: "ok"` is the branch that returns `verified: true`. One tier ran. Nothing escalated, nothing was
flagged, and the user was handed a bill $9.50 short of their receipt with no notice of any kind.

## Defect 1 — the model rescaled dollars to cents (fixed)

Every amount is off by exactly its cents and never by anything else. That is not OCR noise; noise is
random. It is the signature of `floor(printed) × 100`. The cause was in the schema:

```ts
line_total_subunits: { type: "integer", description: "… in minor units (cents) …" }
```

which made the model responsible for the ×100 on every figure. It performed it by keeping the printed
number's integer part and appending two zeros. **The digits were read correctly and then destroyed by
arithmetic we asked for and did not need.**

**Fixed:** every amount is now a string of the digits as printed (`"22.74"`), pattern-constrained.
`amount.ts` converts, in integer arithmetic — never `Number(x) * 100`, since `19.49 * 100` is
`1948.9999999999998`.

## Defect 2 — the gate could not fail (fixed)

`reconciles()` had two branches. The real one, against the printed total, **correctly failed** (94.00 vs
103.50). The second one passed, and it was self-referential: the schema told the model that
`subtotal_subunits` "must equal the sum of every `line_total_subunits` above", so a model that misread
every line summed its own misreadings and matched itself. It compared the model's arithmetic to the
model's arithmetic and asked nothing of the receipt.

**Fixed:** there is exactly one reconciliation, in `billMath.computeBill` — our sum of the model's
readings against the grand total the model only copied. Printed subtotals are diagnostics
(`math.printedSubtotals`) and validate nothing.

## Why tax was 0 and not 6.32

Not a third defect. A consequence of defect 2, and the sharpest evidence for it.

The receipt is internally exact, and its structure is the tell:

```
  items                       107.68     ← every field the model captured is at or above this point
− discount                     14.24     ← captured (as 14.00)
+ Srv Fee (4% of 93.44)         3.74     ← captured (as 3.00)
  ────────────────────────────────────
  Subtotal                     97.18     ← the receipt's SECOND printed subtotal
+ Tax                           6.32     ← the ONLY charge printed below it — the ONLY one dropped
  ────────────────────────────────────
  Total                       103.50
```

Every figure the model captured sits at or above the printed `Subtotal 97.18` line. The one extras field
it dropped is the only one below it. That correlation is exact, and the old schema explains it: `subtotal`
was described *both* as "the subtotal printed BEFORE tax/gratuity/tip/discount" *and* as "must equal the
sum of every `line_total_subunits` above". **On this receipt those two sentences name different numbers**
(107.68 and 97.18) — an instruction with no correct answer. The model resolved it by anchoring on the
pre-discount region, as the second sentence demanded. Having committed to reporting a `subtotal` of
107.68, the receipt's own `Subtotal 97.18` and everything below it read as a totals computation it had
already answered differently. Tax was the only charge living in that zone.

Two honest caveats. We cannot **prove** this for *this* scan: nothing recorded the draft the model returned,
and re-running the photo needs both the original image and an API key, neither of which was available. That
gap is now closed (`raw_draft`, below), so the next one is a query rather than an inference. And the
alternative story — that the model, having been told "subtotal + tax + gratuity + tip − discount must
equal the grand total", zeroed the field it was least sure of to get closer to a balance — is also
arithmetic-induced, and is also fixed by the same change. Both roads lead to the same rule.

**Fixed:** there is no `subtotal` field to be contradictory about. The model lists **every line of the
totals block as printed, in order, with its printed label**, and tags each with what it is. Transcribing
"Tax" and "6.32" is reading; deciding Tax belongs in the tax bucket is classification; neither is
arithmetic. "Which of these two subtotals is *the* subtotal" is a question we stopped asking, because we
no longer need an answer.

## The verify turn — the model checks our arithmetic, not its own

`billMath.computeBill` runs on every scan, unconditionally. When our sum disagrees with the printed total,
the model gets **one** follow-up turn: its own draft replayed as the assistant message, and a tool_result
containing our working, line by line, and the size of the gap. It is asked to correct a **reading**, which
is its job — never to adjust a figure so the sum comes out, which is the fabrication that produces a
self-consistent wrong bill. What it actually sees:

```
I added up what you transcribed. I did the arithmetic, not you — these are your figures, summed:

  22.00  Bacon Avocado Burger
  38.00  Strawberry Mint Sparkler (qty 3)
  …
  ----------------------------
  105.00  sum of the 5 item lines
  + 3.00  service charge / gratuity
  - 14.00  discount
  ----------------------------
  94.00  what your figures come to

You transcribed the printed grand total as 103.50. That is off by 9.50.
…do NOT put the difference somewhere to make the sum come out…
```

Three deliberate choices:

- **It is not a model-invocable tool.** Exposing the math as a tool the model *may* call puts "does the
  arithmetic happen at all" in the model's hands. Running it in code every time does not.
- **The second reading is kept only if its residual is strictly smaller.** A model that "corrects" itself
  further away has started inventing, and an unchanged residual means it found nothing — in both cases the
  first reading, made without pressure to change something, is the honest one.
- **One turn, then escalate.** A model that has looked twice and still disagrees will not converge on a
  third look; the stronger tier is the better spend.

## Cost

Unchanged on a clean scan: one call, and the verify turn only fires on a discrepancy — the same shape as
the existing escalation. `tiers_used` records `primary:verify` so the frequency is measurable rather than
assumed.

---

## Shipped

**`other_charges` end to end.** A delivery fee, bottle deposit, bag fee, or card surcharge now has a real
slot instead of being folded into gratuity. Server-side first, per the non-negotiables: the column landed on
`expenses` before the Room entity existed, and `merge_expense` and `_reprice_web_bill` were both patched —
a Zone-2 column an RPC does not name is dropped from the bill's total on every merge, and its absence from
`merge_expense`'s self-supersede equality check would have made an other-charges-only edit look "identical
to canonical" and be silently discarded. It splits proportionally like tax. Because the money math is
implemented twice, this is done in Kotlin (`BillSplit.kt`), in the TS port (`web/src/lib/money/`), in
`web-claim`'s bill payload, and as a recorded vector (`otherCharges_proportional_likeTax`) that both engines
run — plus a unit test that would have caught the old behaviour. Room is at version 25 with `25.json`
exported.

**Raw draft capture.** `receipt_scan_log` gained `raw_draft` (the tool input verbatim, before
normalization), `residual_subunits` (our computed total minus the printed one), and `verified`. The tax
question above is an inference precisely because none of this was recorded; the next one is a query. The
stored draft tracks the reading that was *kept*, including a verify turn's correction.

**The user is told.** Traced end to end: server `verified: false` → `ExtractResp.verified` →
`ReceiptDraft.verified` → `EditBillState.verified` → `showUnverifiedNotice` → `UnverifiedReceiptNotice`, and
**both** editors wire it (`BillEditScreen` and `ItemizedExpenseBody`, the latter being the path most people
take: add expense → itemize → scan).

## Open

**Measure before tuning the model.** The primary tier runs `thinking: disabled` at `effort: "low"`, which is
defensible for pure transcription and may still be right. Do not change it on a hunch. The lab
(`tools/receipt-ocr-lab`) now imports the real schema, prompt, and math instead of a copy that had drifted
three changes behind, so a comparison there finally means something: run a batch of real receipts across
`primary` / `sonnet-5-thinking` / `escalation` and compare reconciliation rate against cost and latency.
Needs an `ANTHROPIC_API_KEY` in the shell and a folder of real receipts.

**Watch the residual.** Now that it is recorded, the query worth running after a few dozen scans:

```sql
select outcome, count(*), percentile_cont(0.5) within group (order by abs(residual_subunits)) as median_gap
from receipt_scan_log where residual_subunits is not null group by outcome;
```

A rising median is misreads creeping up, and is the early warning that did not exist for this incident.

## Not doing

- **Re-running the failed scan through a stronger model as proof.** The escalation tier reads the same
  photo with the same schema; under the old schema it would have made the same conversion. Model choice
  was not the defect.
- **Blocking the user on an unreconciled draft.** The editor is the confirmation step and every amount is
  editable. The failure was a *silent* wrong number, not an insufficiently strict one.
