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

All amounts are integer **minor units** (cents) to match the app's subunit convention — no
floating-point money crosses the wire. Model: `claude-haiku-4-5` (cheap, fast, plenty for receipts).
