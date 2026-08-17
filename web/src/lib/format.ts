/**
 * Display helpers. Money arrives as integer subunits and stays that way — nothing here does
 * arithmetic, it only renders (see `money/` for the arithmetic, and never mix the two).
 *
 * The bill is shown in **its own currency**, never converted to the guest's locale currency
 * (spec §12.3). A guest converting in her head is a guest paying the wrong number.
 */

/** Currencies whose smallest unit is the unit itself, so "1234" is ¥1,234 and not ¥12.34. */
const ZERO_DECIMAL = new Set(['JPY', 'KRW', 'VND', 'CLP', 'ISK', 'UGX', 'RWF', 'XAF', 'XOF', 'KMF', 'DJF', 'GNF', 'PYG', 'VUV']);
const THREE_DECIMAL = new Set(['BHD', 'IQD', 'JOD', 'KWD', 'LYD', 'OMR', 'TND']);

export function minorUnits(currency: string): number {
  const code = currency?.toUpperCase() ?? 'USD';
  if (ZERO_DECIMAL.has(code)) return 0;
  if (THREE_DECIMAL.has(code)) return 3;
  return 2;
}

/** `$34.20`. Falls back to the plain code when a runtime doesn't know the currency. */
export function money(subunits: number, currency: string): string {
  const digits = minorUnits(currency);
  const value = subunits / 10 ** digits;
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: currency || 'USD',
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(value);
  } catch {
    return `${value.toFixed(digits)} ${currency ?? ''}`.trim();
  }
}

/** `34.20` — the amount column, where the currency symbol would just repeat down the page. */
export function amount(subunits: number, currency: string): string {
  const digits = minorUnits(currency);
  return (subunits / 10 ** digits).toFixed(digits);
}

/** Parses "12.34", "$12.34", "12,34" into subunits. Returns null when there is no number in there. */
export function parseAmount(raw: string, currency: string): number | null {
  const cleaned = raw.replace(/[^0-9.,-]/g, '').replace(/,/g, '.');
  if (!cleaned || !/[0-9]/.test(cleaned)) return null;
  const value = Number(cleaned);
  if (!Number.isFinite(value)) return null;
  return Math.round(value * 10 ** minorUnits(currency));
}

/** `28 Jul` — the evidence strip's date. Input is the DB's `expense_date`. */
export function shortDate(iso: string): string {
  const d = new Date(iso.length <= 10 ? `${iso}T00:00:00` : iso);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' }).format(d);
}

/** The initial in an avatar chip. Grapheme-safe enough for the names people actually type. */
export function initial(name: string): string {
  return [...(name ?? '').trim()][0]?.toUpperCase() ?? '?';
}

/** "2 days left" / "4 hours left" — the link's remaining life, never a raw timestamp. */
export function expiresIn(expiresAt: number, now: number = Date.now()): string {
  const ms = expiresAt - now;
  if (ms <= 0) return 'expired';
  const hours = Math.floor(ms / 3_600_000);
  if (hours >= 48) return `${Math.floor(hours / 24)} days left`;
  if (hours >= 1) return `${hours} ${hours === 1 ? 'hour' : 'hours'} left`;
  const minutes = Math.max(1, Math.floor(ms / 60_000));
  return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'} left`;
}

/** How each payment app spells its own name — the DB stores a lowercase key, people read a brand. */
const PAYMENT_APPS: Record<string, string> = {
  venmo: 'Venmo',
  cashapp: 'Cash App',
  paypal: 'PayPal',
  zelle: 'Zelle',
};

export function paymentAppName(app: string | null): string {
  if (!app) return 'their app';
  return PAYMENT_APPS[app.toLowerCase()] ?? app;
}

/** The two store listings, named once so the claim footnote and the marketing site can't drift apart. */
export const APP_STORE_URL = 'https://apps.apple.com/app/evenly';
export const PLAY_STORE_URL = 'https://play.google.com/store/apps/details?id=app.splitevenly';

export const WAITLIST_URL = '/waitlist';

/**
 * Neither store listing exists yet, so every "get the app" affordance would be a dead link. While
 * this is true they all resolve to the waitlist instead.
 *
 * **Flip this to `false` on the day both listings are live, and nothing else.** Every call site in
 * the bundle routes through the three helpers below precisely so the switch is one line rather than
 * a hunt through five components. The copy that changes with it (`Get early access` vs `Get the
 * app`) reads this same constant.
 */
export const PRE_LAUNCH = true;

/** Where the App Store badge points. */
export function appStoreLink(): string {
  return PRE_LAUNCH ? WAITLIST_URL : APP_STORE_URL;
}

/** Where the Google Play badge points. */
export function playStoreLink(): string {
  return PRE_LAUNCH ? WAITLIST_URL : PLAY_STORE_URL;
}

/** Routes the install footnote to the right store, by user agent (spec §2.8). */
export function storeUrl(): string {
  if (PRE_LAUNCH) return WAITLIST_URL;
  const ua = navigator.userAgent ?? '';
  if (/iPhone|iPad|iPod/i.test(ua)) return APP_STORE_URL;
  if (/Android/i.test(ua)) return PLAY_STORE_URL;
  return 'https://split-evenly.app';
}
