/**
 * The build-time half of the site: the same marketing components, compiled for the server so
 * `prerender.mjs` can write real HTML into `dist/`.
 *
 * It exists because this bundle is a static SPA and the landing page is now the thing the whole
 * business depends on being found. Shipping `<div id="app"></div>` and hoping the crawler runs the
 * bundle costs a rendering round trip at best, and everything at worst.
 *
 * Only the marketing routes are here. The claim flow needs a live bill and is `noindex` anyway, so
 * prerendering it would be work in service of a page no crawler may keep.
 */
import { render } from 'svelte/server';
import type { Component } from 'svelte';
import Waitlist from './marketing/Waitlist.svelte';
import Home from './marketing/Home.svelte';
import Privacy from './marketing/Privacy.svelte';
import Terms from './marketing/Terms.svelte';
import DeleteAccount from './marketing/DeleteAccount.svelte';
import Support from './marketing/Support.svelte';
import { PAGES, SITE_ORIGIN, type PageKind } from './lib/seo.ts';

const COMPONENTS = {
  waitlist: Waitlist,
  home: Home,
  privacy: Privacy,
  terms: Terms,
  'delete-account': DeleteAccount,
  support: Support,
} as const;

/** Path each prerendered page is served at. `/` and `/waitlist` are the same page, twice. */
export const PRERENDERED: { kind: keyof typeof COMPONENTS; path: string; file: string }[] = [
  { kind: 'waitlist', path: '/', file: 'index.html' },
  { kind: 'waitlist', path: '/waitlist', file: 'waitlist.html' },
  { kind: 'home', path: '/home', file: 'home.html' },
  { kind: 'privacy', path: '/privacy', file: 'privacy.html' },
  { kind: 'terms', path: '/terms', file: 'terms.html' },
  { kind: 'delete-account', path: '/delete-account', file: 'delete-account.html' },
  { kind: 'support', path: '/support', file: 'support.html' },
];

export function renderPage(kind: keyof typeof COMPONENTS): { body: string; head: string } {
  // `StoreBadges` still uses the legacy `export let` API, which widens the union enough that
  // `render`'s overloads stop resolving. Every component here takes no props, so the cast is the
  // narrowing rather than a claim about a shape.
  const { body, head } = render(COMPONENTS[kind] as unknown as Component<Record<string, never>>);
  return { body, head };
}

export { PAGES, SITE_ORIGIN };
export type { PageKind };
