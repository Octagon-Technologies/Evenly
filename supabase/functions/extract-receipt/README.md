# `extract-receipt` — receipt OCR for "Split the bill"

Turns a receipt photo into a structured, **editable** draft of a bill (line items + tax / gratuity /
tip / discount) by asking Claude vision for forced structured output. It produces a *first draft only* —
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
POST  { "imageBase64": "<base64>", "mediaType": "image/jpeg" }     # bytes inline (the usual client path)
  or  { "storagePath": "receipts/<path>.jpg" }                     # already uploaded to the bucket
```

Send the project anon (or service-role) key as the `Authorization: Bearer` — the same as every other
authenticated call.

## Response

```jsonc
{
  "configured": true,
  "verified": true,          // false if no tier's draft passed validation — show a "please check" notice
  "receipt": {
    "currency": "USD",
    "items": [{ "label": "Margherita pizza", "quantity": 2, "unit_price_subunits": 1800 }],
    "tax_subunits": 1600,
    "gratuity_subunits": 3168,
    "tip_subunits": 0,
    "discount_subunits": 0,
    "detected_total_subunits": 24368
  }
}
```

Or, when the model is confident the photo isn't a receipt at all (a selfie, a ride-share summary, an
unrelated app screenshot):

```jsonc
{ "configured": true, "noReceipt": true }
```

All amounts are integer **minor units** (cents) to match the app's subunit convention — no
floating-point money crosses the wire.

## Three-tier cascade

Haiku → Sonnet → Opus, cheapest first. Each tier's draft is checked against two independent gates
before being trusted:

- **Not degenerate** — catches a model giving up and lumping the whole bill into one generic line
  (`items.length == 0`, a generic label like "Receipt"/"Total", or a single item with no tax/tip/discount
  that exactly equals the total — this reconciles perfectly but is useless, which is why it needs its own
  check separate from reconciliation).
- **Reconciles** — `Σ(items) + tax + gratuity + tip − discount` matches `detected_total_subunits` within
  1¢ (and the total must be > 0, so a blank/unreadable scan can't "reconcile" via 0 = 0).

A tier that fails escalates to the next. A tier that reports `is_receipt: false` ends the cascade
**immediately** — no further paid calls — since a non-receipt photo doesn't get more receipt-like on a
stronger model; the response is `{ configured: true, noReceipt: true }`. If every tier is exhausted (or
the Opus circuit breaker below trips) without a validated draft, the response carries the best available
guess with `verified: false` — never silently trusted, always still editable client-side.

## Cost guardrails

- **Per-user rate limit** (existing): 20 scans/hour, enforced via `receipt_scan_log`, keyed to the
  caller's own JWT.
- **Org-wide Opus circuit breaker** (new): `receipt_opus_escalations` caps Opus calls across **all**
  users to `OPUS_DAILY_CAP` (env var, default 200) in a rolling 24h window. Once tripped, the cascade
  stops before Opus and returns the last tier that actually ran (Sonnet's draft) with `verified: false`
  rather than spending on the priciest tier. Fails closed: if the breaker check itself errors, Opus is
  skipped.
