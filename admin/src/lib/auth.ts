/**
 * Google sign-in, and nothing else.
 *
 * This is the one place a Supabase client exists in either web bundle, and its whole job is to obtain
 * a JWT. **It never reads a table.** Every admin table has RLS on with no policies and grants revoked
 * from `anon`/`authenticated`, so the anon key here is a login credential and not a data credential:
 * a `supabase.from('waitlist_signups').select()` added to this file would return `42501 permission
 * denied`, and the fact that it would is the point.
 *
 * Access is decided server-side, in the `admin` edge function, against the `admin_users` allowlist
 * (spec §2.3). Signing in with Google gets you a JWT and nothing more. There is no browser-side
 * `isAdmin` flag here because there is nothing useful one could gate.
 */

import { createClient, type Session } from '@supabase/supabase-js';

const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL as string | undefined;
const SUPABASE_ANON_KEY = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;

/** Null when the env vars are absent, so an unconfigured deploy shows a clear message instead of
 *  throwing at module load and rendering a blank page. */
export const supabase = SUPABASE_URL && SUPABASE_ANON_KEY
  ? createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
      auth: {
        persistSession: true,
        autoRefreshToken: true,
        // The OAuth redirect comes back with the code in the URL; this consumes it on load.
        detectSessionInUrl: true,
        // PKCE rather than the implicit flow: no access token is ever put in a URL fragment, so it
        // cannot end up in history, a referrer, or a screen-share of the address bar.
        flowType: 'pkce',
      },
    })
  : null;

export const isConfigured = supabase !== null;

export async function signInWithGoogle(): Promise<{ error: string } | null> {
  if (!supabase) return { error: 'not_configured' };
  const { error } = await supabase.auth.signInWithOAuth({
    provider: 'google',
    options: {
      redirectTo: window.location.origin,
      // Forces the account chooser. Without it, a browser signed into several Google accounts picks
      // silently, and the one it picks is the one that is not on the allowlist.
      queryParams: { prompt: 'select_account' },
    },
  });
  return error ? { error: error.message } : null;
}

export async function signOut(): Promise<void> {
  await supabase?.auth.signOut();
}

export async function currentSession(): Promise<Session | null> {
  if (!supabase) return null;
  const { data } = await supabase.auth.getSession();
  return data.session;
}

/** The bearer token for the `admin` function. Refreshed by the client when it is close to expiry, so
 *  a dashboard left open overnight keeps working rather than 401ing on the first click. */
export async function accessToken(): Promise<string | null> {
  return (await currentSession())?.access_token ?? null;
}
