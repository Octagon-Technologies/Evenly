# receipt-ocr-lab

Local test bench for the receipt OCR extraction used by "Split the bill". Upload a batch of
real receipt photos, pick a model, and see per-image extraction results side by side: latency,
cost, and the structured draft — using the exact same tool schema and prompt as
`supabase/functions/extract-receipt/index.ts`, so results here are a valid stand-in for what the
deployed edge function would produce.

## The API key

The lab finds the key itself (`server/apiKey.js`), so it never has to be pasted into a command line
where it lands in shell history. First hit wins:

1. **`~/.config/anthropic/receipt-lab.key`** — recommended. Outside the repo, so no `.gitignore` rule
   stands between the key and a commit.

   ```sh
   mkdir -p ~/.config/anthropic
   # paste the key into that file with an editor, then:
   chmod 600 ~/.config/anthropic/receipt-lab.key
   ```

2. **`tools/receipt-ocr-lab/.env.local`** — `ANTHROPIC_API_KEY=sk-ant-...`. Gitignored by the root
   `.env*.local` rule. **A plain `.env` is NOT ignored** — `git check-ignore` disagrees with the habit
   here, so use `.env.local` or option 1.

3. **`ANTHROPIC_API_KEY` in the environment** — works, but ends up in shell history.

Either file may hold the bare key or an `ANTHROPIC_API_KEY=...` line. Startup logs only *where* the key
came from, never the value or a prefix of it.

The key the deployed edge function uses is a separate Supabase secret
(`supabase secrets set ANTHROPIC_API_KEY`), and cannot be read back out — so the lab needs its own copy.

## Run

```sh
cd tools/receipt-ocr-lab
npm install
npm start
```

Open http://localhost:4173, pick a model, drop in photos. Each image processes in parallel and
fills in its own card as it finishes.

Providers `primary` and `escalation` mirror the two tiers the deployed function actually runs, sharing
its schema, prompt, and math by import (`supabase/functions/extract-receipt/`) rather than by copy.

## Adding a model to test

Add one entry to the `PROVIDERS` array in `server/providers.js` — `id`, `label`, `model`, pricing,
and a `call()` that returns `{ receipt, usage }`. It shows up in the dropdown automatically; no
other file changes.
