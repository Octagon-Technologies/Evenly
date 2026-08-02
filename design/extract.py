#!/usr/bin/env python3
"""
Decode the Evenly Claude-Design export into readable source.

The standalone HTML is a self-unpacking bundle with two data blocks:

  <script type="__bundler/manifest"> : a UUID-keyed JSON map; each value is
        {"mime", "compressed", "data"} where `data` is base64 (gzip when
        compressed). Holds the authored JS/JSX modules + React vendor bundles
        + woff2 fonts.
  <script type="__bundler/template"> : a JSON-encoded HTML shell whose <style>
        blocks contain the complete design-system CSS (tokens + every .sc-*
        component class) and the @font-face declarations.

Run:  python3 extract.py [path-to-standalone.html]
Writes the authored modules and the full design.css into ./src/.
"""
import base64
import gzip
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
HTML = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "Evenly-Standalone.html")
OUT = os.path.join(HERE, "src")

# Authored modules are commented with their original filename on line 1
# (e.g. `// screens-auth.jsx — ...`); vendor bundles are not.
NAME_RE = re.compile(r"^\s*//\s*([A-Za-z0-9_.\-]+\.(?:jsx|js))")


def script_block(html: str, kind: str) -> str:
    start = html.find(f'<script type="__bundler/{kind}">')
    assert start != -1, f"missing {kind} block"
    start += len(f'<script type="__bundler/{kind}">')
    end = html.find("</script>", start)
    return html[start:end].strip()


def main() -> None:
    html = open(HTML, encoding="utf-8", errors="replace").read()
    os.makedirs(OUT, exist_ok=True)

    # 1) authored modules from the manifest -------------------------------
    manifest = json.loads(script_block(html, "manifest"))
    written = []
    for uid, ent in manifest.items():
        if ent["mime"] not in ("text/javascript", "application/javascript", "text/jsx"):
            continue  # skip woff2 fonts
        raw = base64.b64decode(ent["data"])
        if ent.get("compressed"):
            raw = gzip.decompress(raw)
        text = raw.decode("utf-8", "replace")
        m = NAME_RE.match(text)
        if not m:
            continue  # skip React/ReactDOM/runtime vendor bundles
        with open(os.path.join(OUT, m.group(1)), "w") as f:
            f.write(text)
        written.append(m.group(1))

    # 2) full design CSS from the template's <style> blocks ---------------
    template = json.loads(script_block(html, "template"))
    styles = re.findall(r"<style[^>]*>(.*?)</style>", template, re.S)
    styles = [s for s in styles if "--blue" in s or "@font-face" in s or ".sc-" in s]
    css = "\n\n".join(s.strip() for s in styles)
    with open(os.path.join(OUT, "design.css"), "w") as f:
        f.write(css)

    print(f"wrote {len(written)} modules + design.css ({len(css)} bytes) to {OUT}")
    for n in sorted(written):
        print("  ", n)


if __name__ == "__main__":
    main()
