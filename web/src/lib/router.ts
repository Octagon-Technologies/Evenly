/**
 * `/b/:token` is the whole route table (spec §3.1). Static SPA: the host rewrites every path to
 * `index.html` (see `public/_redirects`) and the token is read back off the pathname here.
 *
 * The token is a 128-bit base62 value, so it is matched by shape rather than trusted — a malformed
 * path gets the "no token" screen instead of a request that could only ever fail.
 */

const BILL_PATH = /^\/b\/([0-9A-Za-z]{16,64})\/?$/;

export function billTokenFromLocation(loc: { pathname: string; search: string } = window.location): string | null {
  const match = BILL_PATH.exec(loc.pathname);
  if (match) return match[1];
  // Dev and QR-less testing: `?b=<token>` works anywhere, including the Vite dev server root.
  const query = new URLSearchParams(loc.search).get('b');
  return query && /^[0-9A-Za-z]{16,64}$/.test(query) ? query : null;
}

/** The link a payer hands out — used by the "share" copy on the summary. */
export function billUrl(token: string): string {
  return `${window.location.origin}/b/${token}`;
}

/**
 * The site's whole route table, now that it also carries the marketing pages (home, privacy,
 * terms) alongside the claim flow. A bill token, wherever it's found, always wins — a malformed
 * `/b/:token` still isn't a route this table knows, so it falls through to the landing page rather
 * than a broken claim screen.
 *
 * **Pre-launch, `/` *is* the waitlist.** There is no app to install yet, so a landing page whose
 * job is to send people to a store is a page that sends them nowhere; every visit is worth an
 * email address instead. `/waitlist` renders the same component so existing links keep working, and
 * `seo.ts` canonicalises it to `/` so the two URLs are never indexed as duplicates. The editorial
 * home page is parked at `/home` (kept, unlinked and `noindex`) and comes back to `/` on launch day
 * with `PRE_LAUNCH` in `format.ts`.
 */
export type Route =
  | { kind: 'bill'; token: string }
  | { kind: 'home' }
  | { kind: 'privacy' }
  | { kind: 'terms' }
  | { kind: 'delete-account' }
  | { kind: 'support' }
  | { kind: 'waitlist' }
  | { kind: 'admin' };

export function resolveRoute(loc: { pathname: string; search: string } = window.location): Route {
  const token = billTokenFromLocation(loc);
  if (token) return { kind: 'bill', token };
  const path = loc.pathname.replace(/\/$/, '') || '/';
  if (path === '/privacy') return { kind: 'privacy' };
  if (path === '/terms') return { kind: 'terms' };
  if (path === '/delete-account') return { kind: 'delete-account' };
  if (path === '/support') return { kind: 'support' };
  if (path === '/waitlist') return { kind: 'waitlist' };
  if (path === '/home') return { kind: 'home' };
  if (path === '/admin') return { kind: 'admin' };
  return { kind: 'waitlist' };
}
