# receipt-ocr-lab

Local test bench for the receipt OCR extraction used by "Split the bill". Upload a batch of
real receipt photos, pick a model, and see per-image extraction results side by side: latency,
cost, and the structured draft — using the exact same tool schema and prompt as
`supabase/functions/extract-receipt/index.ts`, so results here are a valid stand-in for what the
deployed edge function would produce.

## Run

```sh
cd tools/receipt-ocr-lab
npm install
export ANTHROPIC_API_KEY=sk-ant-...
npm start
```

Open http://localhost:4173, pick a model, drop in photos. Each image processes in parallel and
fills in its own card as it finishes.

## Adding a model to test

Add one entry to the `PROVIDERS` array in `server/providers.js` — `id`, `label`, `model`, pricing,
and a `call()` that returns `{ receipt, usage }`. It shows up in the dropdown automatically; no
other file changes.
