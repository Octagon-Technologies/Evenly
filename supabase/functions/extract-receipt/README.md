# `extract-receipt` — receipt OCR for "Split the bill"

Turns a receipt photo into a structured, **editable** draft of a bill (line items + tax / gratuity /
tip / discount / other charges) by asking Claude vision for forced structured output. It produces a *first draft only* —
the client always lands the user on the editable item list to confirm before any money is computed, and
reconciles `Σ items + extras` against the receipt's printed total so a misread is flagged, never trusted.

## Configure (inert until set)

```bash
supabase secrets set ANTHROPIC_API_KEY=sk-ant-...
supabase functions deploy extract-receipt
```

Without the secret the function returns `200 { "configured": false }`, so the client cleanly falls back
to manual entry instead of surfacing an error.

## Request

```
POST  { "files": [{ "data": "<base64>", "mediaType": "image/jpeg" | "application/pdf" }, …] }  # the client path
  or  { "imageBase64": "<base64>", "mediaType": "image/jpeg" }   # single image inline (legacy)
  or  { "storagePath": "receipts/<path>.jpg" }                   # already uploaded to the bucket
```

`files` may mix photos and PDFs (up to 8), read together as **one** bill. An optional `groupId` attributes
the scan for cost-per-group reporting.

Send the **signed-in user's access token** as `Authorization: Bearer`, with the anon key in the `apikey`
header. The function 401s without a resolvable user: the per-user rate limit is keyed to `auth.uid()`, and
an anon-key-only caller used to bypass it entirely on a paid endpoint.

## Response

```jsonc
{
  "configured": true,
  "verified": true,          // false if no tier's draft reconciled — show a "please check" notice
  "scanId": "…",             // this scan's receipt_scan_log row
  "receipt": {
    "currency": "USD",
    "items": [{ "label": "Bacon Avocado Burger", "quantity": 1, "line_total_subunits": 2274 }],
    "tax_subunits": 632,
    "gratuity_subunits": 374,
    "tip_subunits": 0,
    "discount_subunits": 1424,
    "other_charges_subunits": 0,
    "detected_total_subunits": 10350
  }
}
```

Or, when the model is confident the photo isn't a receipt at all (a selfie, a ride-share summary, an
unrelated app screenshot):

```jsonc
{ "configured": true, "noReceipt": true }
```

All amounts are integer **minor units** (cents) to match the app's subunit convention — no floating-point
money crosses the wire. **The client's wire shape is unchanged by the 2026-08-08 rewrite**: `summary` is an
extraction detail the app never sees, flattened into the five extras by `clientReceipt()`.

`other_charges_subunits` carries a printed charge that is none of the other four: a delivery fee, bottle
deposit, bag fee, or card surcharge. It splits proportionally like tax, end to end (server column, Room
entity, both money engines, the bill editor). It briefly had no slot and was folded into gratuity, which
kept the total honest and mislabelled the row the user saw.

## The model transcribes. Code computes. No exceptions.

**No field in the `record_receipt` schema holds a value the model has to work out, and the prompt contains
no instruction to add, check, or balance anything.** Amounts are the digits as printed — the string
`"22.74"`, not the integer `2274`. Every sum, difference, and comparison happens in `billMath.ts`, in
integer arithmetic, over figures the model only copied.

This is not a style preference. Two production defects on 2026-08-08, one receipt, one cause:

1. `line_total_subunits: integer` made the model do the dollars→cents rescale. It did it by keeping the
   printed number's integer part and appending two zeros — `$22.74 → 2200`, `$3.74 → 300` — so a $103.50
   restaurant bill reached the editor as **$94.00** with every cent discarded.
2. `subtotal_subunits` was described as "must equal the sum of every `line_total_subunits` above". That
   made the reconciliation gate **self-referential** — the model summed its own misreadings and matched
   itself, so the wrong bill shipped as `verified: true` — and on a receipt printing both a pre- and a
   post-discount subtotal it was an instruction with no correct answer, which is why `Tax 6.32`, the one
   charge printed below the second subtotal, came back as 0.

There is no `subtotal` field now. The model lists every line of the totals block as printed, in order,
with its label, and tags each with what it is. Reading a line is transcription; saying it is a tax line is
classification; neither is arithmetic.

> **Never add a gate whose two sides both come from the model.** It cannot fail, and it will read like
> safety in the diff.

## The verify turn

`computeBill` runs on every scan. If our sum disagrees with the printed grand total, the model gets **one**
follow-up turn: its own draft replayed as the assistant message, plus a tool_result containing our working
line by line and the size of the gap. It is asked to correct a *reading*, never to adjust a figure so the
sum comes out. The second reading is kept only if its residual is strictly smaller — a model that
"corrects" itself further away has started inventing. Then, and only then, the escalation tier.

The math is deliberately **not** a tool the model can choose to call: that would put "does the arithmetic
happen at all" in the model's hands.

## Gates

A draft is trusted only if all three hold (`isValidDraft`):

- **Has amounts** — at least one priced line. A draft of real dish names at 0.00 each is a failed read
  wearing a success costume, and is reported as unreadable rather than handed back.
- **Not degenerate** — catches a model lumping the whole bill into one generic line (empty items, a
  whole-label match on "Receipt"/"Total", or a single item with no extras equal to the total — that
  reconciles perfectly and is useless, so it needs its own check).
- **Reconciles** — our `Σ items + tax + gratuity + tip + other − discount` is within 2¢ of the **printed**
  grand total. One branch, on purpose.

A `max_tokens` stop is a failed pass and its draft is discarded, never kept as the fallback. A tier
reporting `is_receipt: false` ends everything immediately — no further paid calls. If both tiers are
exhausted (or the escalation breaker trips) without a reconciling draft, the response carries the best
guess with `verified: false` — never silently trusted, always editable client-side.

## Tests

```bash
node --experimental-strip-types --test "supabase/functions/extract-receipt/*.test.ts"
```

Both suites carry the receipt that regressed as a regression vector: `amount.test.ts` pins the cents,
`billMath.test.ts` pins that the truncated draft now fails reconciliation instead of passing as verified.

## Files

| File               | What                                                                        |
| ------------------ | --------------------------------------------------------------------------- |
| `index.ts`         | HTTP handler, auth + rate limit, tier loop, verify turn, gates, cost ledger  |
| `contract.ts`      | The tool schema and prompt (transcription only), printed digits → subunits   |
| `amount.ts`        | `parseAmountSubunits` — printed money text → integer minor units             |
| `billMath.ts`      | **All arithmetic**: sums, the reconciliation, the verify-turn message        |
| `*.test.ts`        | Regression vectors for both                                                  |
| the scan ledger    | `receipt_scan_log` also stores `raw_draft`, `residual_subunits`, `verified` |

`tools/receipt-ocr-lab` **imports `contract.ts` and `billMath.ts` directly** rather than keeping a copy, so
what you test there is what the deployed function sends. It used to keep a copy, and by the time it
mattered the copy was three changes stale.

## Cost guardrails

- **Per-user rate limit** (existing): 20 scans/hour, enforced via `receipt_scan_log`, keyed to the
  caller's own JWT.
- **Org-wide escalation circuit breaker**: `receipt_opus_escalations` caps escalation-tier calls across
  **all** users to `OPUS_DAILY_CAP` (env var, default 200) in a rolling 24h window. Once tripped, the loop
  stops before Opus and returns the primary tier's draft with `verified: false` rather than spending on
  the priciest tier. Fails closed: if the breaker check itself errors, Opus is skipped.
- **Per-scan cost ledger**: every scan completes its `receipt_scan_log` row with `outcome`, `tiers_used`,
  token counts, `cost_micros`, and `duration_ms`. That ledger is how the cents defect was confirmed to
  have passed the gates on one tier with `outcome = "ok"`.
