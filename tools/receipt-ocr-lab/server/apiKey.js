// Resolves the Anthropic API key for the lab without it ever being pasted into a chat, a command line
// (where it lands in shell history), or a tracked file.
//
// Checked in order, first hit wins:
//   1. process.env.ANTHROPIC_API_KEY      — an export in your shell, or `ANTHROPIC_API_KEY=... npm start`
//   2. ~/.config/anthropic/receipt-lab.key — RECOMMENDED. Outside the repo, so no .gitignore rule stands
//                                            between the key and a commit.
//   3. <repo>/tools/receipt-ocr-lab/.env.local — matched by the root .gitignore's `.env*.local`.
//                                            Note that a plain `.env` is NOT ignored; do not use one.
//
// Files may hold the bare key, or a `KEY=value` line (a `.env`-style file). Whitespace, quotes, and a
// leading `export ` are tolerated. Only the SOURCE is ever logged, never the value or any part of it.

import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const FILE_SOURCES = [
  path.join(homedir(), ".config", "anthropic", "receipt-lab.key"),
  path.join(__dirname, "..", ".env.local"),
];

/** Pulls the key out of a bare-value file or a KEY=value line, without logging any of it. */
function parseKeyFile(text) {
  for (const raw of text.split("\n")) {
    const line = raw.trim().replace(/^export\s+/, "");
    if (!line || line.startsWith("#")) continue;
    const eq = line.indexOf("=");
    const value = eq >= 0 ? line.slice(eq + 1) : line;
    const cleaned = value.trim().replace(/^["']|["']$/g, "");
    if (cleaned) return cleaned;
  }
  return null;
}

/** Returns { key, source } — `key` is null when nothing was found. `source` is safe to print. */
export function resolveApiKey() {
  if (process.env.ANTHROPIC_API_KEY) {
    return { key: process.env.ANTHROPIC_API_KEY, source: "ANTHROPIC_API_KEY in the environment" };
  }
  for (const file of FILE_SOURCES) {
    try {
      const key = parseKeyFile(readFileSync(file, "utf8"));
      if (key) return { key, source: file.replace(homedir(), "~") };
    } catch {
      // Missing or unreadable is the normal case for the paths you're not using.
    }
  }
  return { key: null, source: null };
}

/** The setup message, printed when no key was found. Lists the options rather than assuming one. */
export const SETUP_HINT = [
  "No Anthropic API key found. Put it in ONE of these, then restart:",
  "",
  "  1. ~/.config/anthropic/receipt-lab.key   (recommended — outside the repo entirely)",
  "       mkdir -p ~/.config/anthropic",
  "       # paste the key into that file with your editor, then:",
  "       chmod 600 ~/.config/anthropic/receipt-lab.key",
  "",
  "  2. tools/receipt-ocr-lab/.env.local      (gitignored via the root `.env*.local` rule)",
  "       ANTHROPIC_API_KEY=sk-ant-...",
  "",
  "  3. export ANTHROPIC_API_KEY=sk-ant-...   (lands in your shell history)",
  "",
  "The file may hold the bare key or an ANTHROPIC_API_KEY=... line.",
].join("\n");
