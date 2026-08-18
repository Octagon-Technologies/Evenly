/**
 * Turns the built SPA into real HTML files, one per marketing route.
 *
 * Runs as the second half of `npm run build`. `vite build` produces `dist/index.html` — a shell
 * whose `<head>` is already the landing page's (see `index.html`) and whose `<body>` is an empty
 * `#app`. This fills that `#app` with server-rendered markup and rewrites the head per route, so a
 * crawler, a link unfurler, or a browser with a slow connection gets the page's text without
 * executing anything.
 *
 * The client still mounts normally on top: `main.ts` empties `#app` first, so this is a prerender
 * and not hydration. Hydrating would buy a few milliseconds and cost a whole class of mismatch bug
 * on a page whose hero is an animation.
 */
import { build } from 'vite';
import { readFile, writeFile, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = dirname(fileURLToPath(import.meta.url));
const dist = resolve(root, 'dist');
const ssrDir = resolve(root, '.ssr-prerender');

await build({
  root,
  logLevel: 'warn',
  build: {
    ssr: resolve(root, 'src/prerender-entry.ts'),
    outDir: ssrDir,
    emptyOutDir: true,
    target: 'node22',
  },
});

const { renderPage, PRERENDERED, PAGES, SITE_ORIGIN } = await import(
  resolve(ssrDir, 'prerender-entry.js')
);

const shell = await readFile(resolve(dist, 'index.html'), 'utf8');

/** Replaces the *content* of a tag that `index.html` already ships, rather than appending a second one. */
function setTag(html, pattern, replacement) {
  if (!pattern.test(html)) throw new Error(`prerender: no tag matched ${pattern} — index.html changed shape`);
  return html.replace(pattern, replacement);
}

const escape = (s) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');

for (const { kind, path, file } of PRERENDERED) {
  const seo = PAGES[kind];
  const { body, head } = renderPage(kind);
  let html = shell;

  html = setTag(html, /<title>[\s\S]*?<\/title>/, `<title>${escape(seo.title)}</title>`);
  html = setTag(
    html,
    /<meta\s+name="description"[\s\S]*?\/>/,
    `<meta name="description" content="${escape(seo.description)}" />`,
  );
  html = setTag(
    html,
    /<meta\s+property="og:title"[\s\S]*?\/>/,
    `<meta property="og:title" content="${escape(seo.title)}" />`,
  );
  html = setTag(
    html,
    /<meta\s+name="twitter:title"[\s\S]*?\/>/,
    `<meta name="twitter:title" content="${escape(seo.title)}" />`,
  );

  // A page with no canonical is one we do not want indexed at all (`/home` while the waitlist owns
  // `/`), so the canonical link becomes the `noindex` instead of pointing somewhere.
  html = setTag(
    html,
    /<link rel="canonical"[\s\S]*?\/>/,
    seo.canonical
      ? `<link rel="canonical" href="${SITE_ORIGIN}${seo.canonical}" />`
      : '<meta name="prerender-noindex" content="1" />',
  );
  html = setTag(
    html,
    /<meta name="robots"[\s\S]*?\/>/,
    seo.canonical
      ? '<meta name="robots" content="index, follow, max-image-preview:large" />'
      : '<meta name="robots" content="noindex, nofollow" />',
  );
  html = setTag(
    html,
    /<meta property="og:url"[\s\S]*?\/>/,
    `<meta property="og:url" content="${SITE_ORIGIN}${seo.canonical ?? path}" />`,
  );

  if (head.trim()) html = html.replace('</head>', `${head}\n  </head>`);
  html = html.replace('<div id="app"></div>', `<div id="app">${body}</div>`);

  await writeFile(resolve(dist, file), html, 'utf8');
  console.log(`prerendered ${path} → dist/${file} (${(html.length / 1024).toFixed(0)} kB)`);
}

// The claim flow and the admin placeholder get the shell with an *empty* `#app`, not the landing
// page's markup: a `/b/:token` visit that paints a waitlist hero for one frame before the bill
// arrives is a worse first impression than a blank one, and neither route may be indexed.
const shellHtml = setTag(
  shell,
  /<meta name="robots"[\s\S]*?\/>/,
  '<meta name="robots" content="noindex, nofollow" />',
).replace(/<link rel="canonical"[\s\S]*?\/>/, '');
await writeFile(resolve(dist, 'app.html'), shellHtml, 'utf8');
console.log('wrote dist/app.html (claim + admin shell, noindex)');

await rm(ssrDir, { recursive: true, force: true });
