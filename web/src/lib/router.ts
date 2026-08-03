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
