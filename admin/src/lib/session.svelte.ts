/**
 * Who is looking at this dashboard, as far as the server will say.
 *
 * The phases exist because "signed in" and "allowed in" are two different facts here and only the
 * second one matters. `no_access` is reached by a perfectly valid Google sign-in that has no row in
 * `admin_users`, and it is a screen rather than a redirect: silently bouncing someone back to the
 * sign-in button reads as a broken login, and they would try again forever.
 */

import { api, ApiError } from './api.ts';
import { currentSession, isConfigured, signOut as authSignOut } from './auth.ts';

export type Phase = 'loading' | 'unconfigured' | 'signed_out' | 'no_access' | 'ready' | 'error';

class Session {
  phase = $state<Phase>('loading');
  email = $state('');
  error = $state('');

  async boot(): Promise<void> {
    if (import.meta.env.DEV) {
      const { mockShape } = await import('./mock.ts');
      if (mockShape()) {
        this.email = 'mock@example.com';
        this.phase = 'ready';
        return;
      }
    }

    if (!isConfigured) {
      this.phase = 'unconfigured';
      return;
    }

    // Resolves the OAuth redirect too: `detectSessionInUrl` consumes the `?code=` before this
    // returns, so there is no separate "did we just come back from Google" branch to get wrong.
    const session = await currentSession();
    if (!session) {
      this.phase = 'signed_out';
      return;
    }

    try {
      const me = await api.me();
      this.email = me.email;
      this.phase = 'ready';
    } catch (e) {
      const code = e instanceof ApiError ? e.code : 'SERVER';
      if (code === 'FORBIDDEN') this.phase = 'no_access';
      else if (code === 'UNAUTHENTICATED') this.phase = 'signed_out';
      else if (code === 'NOT_CONFIGURED') this.phase = 'unconfigured';
      else {
        this.error = code === 'NETWORK'
          ? 'Could not reach the server. Check your connection and try again.'
          : 'The server did not answer. Try again in a moment.';
        this.phase = 'error';
      }
    }
  }

  async signOut(): Promise<void> {
    await authSignOut();
    this.email = '';
    this.phase = 'signed_out';
  }
}

export const session = new Session();
