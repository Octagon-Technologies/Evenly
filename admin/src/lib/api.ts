/**
 * The only way this bundle reads data.
 *
 * Every call goes to the `admin` edge function with the OAuth JWT in an Authorization header. The
 * function verifies it and checks `admin_users` before touching a table (spec §2.3), so a signed-in
 * Google account that is not on the list gets a 403 having read nothing.
 *
 * The 403 is the interesting case and is surfaced as its own error code rather than folded into a
 * generic failure: a stranger who signed in successfully needs to be told "this account has no
 * access", not bounced back through the OAuth loop forever.
 */

import { accessToken } from './auth.ts';

const BASE = (import.meta.env.VITE_ADMIN_FN_URL as string | undefined)?.replace(/\/+$/, '') ?? '';

export type ApiErrorCode = 'UNAUTHENTICATED' | 'FORBIDDEN' | 'NOT_CONFIGURED' | 'SERVER' | 'NETWORK';

export class ApiError extends Error {
  code: ApiErrorCode;
  constructor(code: ApiErrorCode, message?: string) {
    super(message ?? code);
    this.name = 'ApiError';
    this.code = code;
  }
}

/** Dev-only `?mock` fixture (see `mock.ts`). Returns null in a build, where `mock.ts` is unreachable
 *  and the whole branch is dropped. */
async function mocked<T>(pick: (m: typeof import('./mock.ts')) => T | null): Promise<T | null> {
  if (!import.meta.env.DEV) return null;
  const m = await import('./mock.ts');
  return m.mockShape() ? pick(m) : null;
}

async function request(action: string, body: Record<string, unknown> = {}): Promise<Response> {
  if (!BASE) throw new ApiError('NOT_CONFIGURED', 'VITE_ADMIN_FN_URL is not set');

  const token = await accessToken();
  if (!token) throw new ApiError('UNAUTHENTICATED');

  let response: Response;
  try {
    response = await fetch(`${BASE}/${action}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(body),
    });
  } catch {
    throw new ApiError('NETWORK');
  }

  if (response.status === 401) throw new ApiError('UNAUTHENTICATED');
  if (response.status === 403) throw new ApiError('FORBIDDEN');
  if (!response.ok) throw new ApiError('SERVER', `HTTP ${response.status}`);
  return response;
}

async function postJson<T>(action: string, body: Record<string, unknown> = {}): Promise<T> {
  return (await (await request(action, body)).json()) as T;
}

export interface WaitlistRow {
  id: string;
  email: string;
  source: string | null;
  created_at: string;
}

export interface WaitlistPage {
  rows: WaitlistRow[];
  total: number;
  limit: number;
  offset: number;
}

export interface WaitlistStats {
  from: string;
  baseline: number;
  total: number;
  /** Dense: one entry per calendar day in the window, zeros included. Pre-launch there are genuine
   *  zero days, and a chart that omits them draws a lie. */
  days: { day: string; n: number }[];
  /** Sparse, for the stacked breakdown. */
  bySource: { day: string; source: string; n: number }[];
}

export type Range = '7' | '30' | '90' | 'all';

export const api = {
  /** Called once on load. Its whole job is to turn "signed in with Google" into "signed in and
   *  allowed", which only the server can answer. */
  me: () => postJson<{ email: string }>('me'),

  waitlist: async (q: string, limit: number, offset: number) =>
    (await mocked((m) => m.mockWaitlist(m.mockShape()!, limit, offset))) ??
    postJson<WaitlistPage>('waitlist', { q, limit, offset }),

  waitlistStats: async (range: Range) =>
    (await mocked((m) => m.mockStats(m.mockShape()!, range))) ??
    postJson<WaitlistStats>('waitlist-stats', { range }),

  /** Returns the CSV text. A browser cannot put an Authorization header on a navigation, so the
   *  download is built from this in the page rather than being a link to the endpoint. */
  waitlistCsv: async (): Promise<string> => (await request('waitlist-export')).text(),
};
