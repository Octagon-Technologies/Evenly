/**
 * Per-route `<head>` state for the marketing site, in one table.
 *
 * The bundle is a static SPA, so `index.html` ships the *landing* page's tags already resolved and
 * this module only has to correct them on the other routes. Two consequences worth knowing before
 * editing either half:
 *  - Whatever `index.html` hard-codes must stay identical to `PAGES['waitlist']` below, or a
 *    crawler that does not run scripts and one that does will read two different pages.
 *  - `/` and `/waitlist` are the same component, so both canonicalise to `/`. Dropping that leaves
 *    two URLs competing for the same query with half the signal each.
 */

export const SITE_ORIGIN = 'https://split-evenly.app';

export type PageKind =
  | 'waitlist' | 'home' | 'privacy' | 'terms' | 'delete-account' | 'support' | 'admin' | 'bill';

type PageSeo = {
  title: string;
  description: string;
  /** Path this page wants to be indexed as, or `null` for the pages that must not be indexed. */
  canonical: string | null;
};

export const PAGES: Record<PageKind, PageSeo> = {
  waitlist: {
    title: 'Evenly — Split the bill by item, no app needed for your friends',
    description:
      'Evenly scans the receipt, splits it by item, and lets everyone at the table claim what they ate from a link in the group chat. No downloads, no accounts, no chasing. Join the waitlist for early access.',
    canonical: '/',
  },
  home: {
    // Parked while `/` is the waitlist: reachable, but never a second door into the same content.
    title: 'Evenly — Split the bill, not the friendship.',
    description:
      'Evenly reads the receipt, itemizes every plate, and works out who owes what to the cent.',
    canonical: null,
  },
  privacy: {
    title: 'Privacy Policy · Evenly',
    description:
      'What Evenly stores, what it never asks for, and how to delete your data. We never see a bank detail, because we never ask for one.',
    canonical: '/privacy',
  },
  terms: {
    title: 'Terms of Service · Evenly',
    description: 'The terms you agree to when you use Evenly to split and settle shared expenses.',
    canonical: '/terms',
  },
  'delete-account': {
    title: 'Delete Your Account · Evenly',
    description: 'How to delete your Evenly account and everything attached to it.',
    canonical: '/delete-account',
  },
  support: {
    title: 'Help & Support · Evenly',
    description: 'Answers to common questions about splitting bills with Evenly, and how to reach us.',
    canonical: '/support',
  },
  admin: { title: 'Admin · Evenly', description: '', canonical: null },
  bill: {
    // A bill link in a chat thread must never render a preview of someone's dinner.
    title: 'Claim your items · Evenly',
    description: '',
    canonical: null,
  },
};

function setMeta(selector: string, attr: 'name' | 'property', key: string, content: string): void {
  let tag = document.head.querySelector<HTMLMetaElement>(selector);
  if (!tag) {
    tag = document.createElement('meta');
    tag.setAttribute(attr, key);
    document.head.appendChild(tag);
  }
  tag.content = content;
}

function setLink(rel: string, href: string): void {
  let tag = document.head.querySelector<HTMLLinkElement>(`link[rel="${rel}"]`);
  if (!tag) {
    tag = document.createElement('link');
    tag.rel = rel;
    document.head.appendChild(tag);
  }
  tag.href = href;
}

/** Applies one row of `PAGES` to the live document. Idempotent — it rewrites, never appends twice. */
export function applySeo(kind: PageKind): void {
  const page = PAGES[kind];
  document.title = page.title;
  if (page.description) {
    setMeta('meta[name="description"]', 'name', 'description', page.description);
    setMeta('meta[property="og:description"]', 'property', 'og:description', page.description);
    setMeta('meta[name="twitter:description"]', 'name', 'twitter:description', page.description);
  }
  setMeta('meta[property="og:title"]', 'property', 'og:title', page.title);
  setMeta('meta[name="twitter:title"]', 'name', 'twitter:title', page.title);

  if (page.canonical) {
    setLink('canonical', `${SITE_ORIGIN}${page.canonical}`);
    setMeta('meta[property="og:url"]', 'property', 'og:url', `${SITE_ORIGIN}${page.canonical}`);
    setMeta('meta[name="robots"]', 'name', 'robots', 'index, follow, max-image-preview:large');
  } else {
    document.head.querySelector('link[rel="canonical"]')?.remove();
    setMeta('meta[name="robots"]', 'name', 'robots', 'noindex, nofollow');
  }
}
