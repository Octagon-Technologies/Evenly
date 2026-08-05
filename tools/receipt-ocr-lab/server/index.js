import express from "express";
import multer from "multer";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { PROVIDERS, getProvider, costUsd } from "./providers.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 20 * 1024 * 1024 } });

const app = express();
app.use(express.static(path.join(__dirname, "..", "public")));

app.get("/api/providers", (_req, res) => {
  res.json(PROVIDERS.map((p) => ({ id: p.id, label: p.label, model: p.model })));
});

// One image per request — the frontend fires these in parallel per upload, so
// each card gets its own independent progress/result without any batching
// logic here.
app.post("/api/extract", upload.single("file"), async (req, res) => {
  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) {
    return res.status(500).json({ error: "ANTHROPIC_API_KEY is not set. Export it before starting the server." });
  }
  if (!req.file) return res.status(400).json({ error: "no file uploaded" });

  const providerId = req.body.provider;
  let provider;
  try {
    provider = getProvider(providerId);
  } catch (e) {
    return res.status(400).json({ error: e.message });
  }

  const pages = [{ mimeType: req.file.mimetype, base64: req.file.buffer.toString("base64") }];

  const startedAt = Date.now();
  try {
    const { receipt, usage } = await provider.call({ apiKey, pages });
    const latencyMs = Date.now() - startedAt;
    if (!receipt) {
      return res.json({ ok: false, latencyMs, usage, cost: costUsd(provider, usage), error: "model returned no structured output" });
    }
    res.json({ ok: true, latencyMs, usage, cost: costUsd(provider, usage), receipt });
  } catch (e) {
    res.json({ ok: false, latencyMs: Date.now() - startedAt, error: e.message ?? String(e) });
  }
});

const PORT = process.env.PORT || 4173;
app.listen(PORT, () => {
  console.log(`receipt-ocr-lab running at http://localhost:${PORT}`);
  if (!process.env.ANTHROPIC_API_KEY) {
    console.warn("⚠️  ANTHROPIC_API_KEY is not set — extraction calls will fail until you export it.");
  }
});
