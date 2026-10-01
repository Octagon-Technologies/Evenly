# Evenly design system

This is the editable, browser-based reference for Evenly's shipped UI. It complements the historical
`design/Evenly-Standalone.html` export and the real Compose implementation under
`code/shared/src/commonMain/kotlin/app/splitevenly/ui/`.

## Delivery phases

1. Foundations and site navigation.
2. Components with complete variants, states, sizes, and Compose usage.
3. Reusable product patterns.
4. Current screens and end-to-end flows.
5. Drift checks, responsive verification, and private publication.

## Editing

The site has no framework or package-manager dependency. Edit `dist/index.html`, `dist/styles.css`,
`dist/catalog.js`, and `dist/app.js`, then serve `dist/` with any static HTTP server.

```bash
python3 -m http.server 4173 --directory design/system/dist
```

Open `http://localhost:4173`. Hash routes make every section directly linkable.

## Source-of-truth boundary

- Compose is authoritative for shipped behavior and rendering.
- This site is authoritative for discoverability, design intent, usage guidance, mockups, and flows.
- `catalog.js` is the editable inventory used by the website.
- `scripts/check-design-system.mjs` checks the documented button and chip variants against Kotlin.

When an `Ev*` component, theme token, route, or screen changes, update the relevant catalog entry in
the same commit.
