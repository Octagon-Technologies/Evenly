<script lang="ts">
  /**
   * One button. Signing in with Google is necessary and not sufficient (spec §2.1): a row in
   * `admin_users` is what grants access, and that check happens on the server after this.
   */
  import Wordmark from '../components/Wordmark.svelte';
  import { signInWithGoogle } from '../lib/auth.ts';

  let busy = $state(false);
  let error = $state('');

  async function go() {
    busy = true;
    error = '';
    const result = await signInWithGoogle();
    if (result) {
      busy = false;
      error = 'Google sign-in could not start. Try again in a moment.';
    }
    // On success the browser navigates to Google, so there is nothing to reset.
  }
</script>

<div class="adm-gate">
  <Wordmark iconOnly />
  <h1>Sign in to Evenly Admin</h1>
  <p>This dashboard is for the Evenly team. Accounts are added by hand.</p>

  {#if error}
    <div class="adm-error" style="margin-bottom:16px;max-width:340px">{error}</div>
  {/if}

  <div class="adm-gate-form">
    <button class="adm-gate-btn" onclick={go} disabled={busy}>
      <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
        <path fill="#4285F4" d="M21.6 12.23c0-.7-.06-1.37-.18-2.02H12v3.82h5.38a4.6 4.6 0 0 1-2 3.02v2.5h3.24c1.9-1.74 2.98-4.3 2.98-7.32z" />
        <path fill="#34A853" d="M12 22c2.7 0 4.96-.9 6.62-2.43l-3.24-2.5c-.9.6-2.05.96-3.38.96-2.6 0-4.8-1.75-5.59-4.11H3.06v2.58A10 10 0 0 0 12 22z" />
        <path fill="#FBBC05" d="M6.41 13.92a6 6 0 0 1 0-3.83V7.5H3.06a10 10 0 0 0 0 9l3.35-2.58z" />
        <path fill="#EA4335" d="M12 5.98c1.47 0 2.79.5 3.83 1.5l2.87-2.87C16.95 2.98 14.7 2 12 2A10 10 0 0 0 3.06 7.5l3.35 2.59C7.2 7.73 9.4 5.98 12 5.98z" />
      </svg>
      Continue with Google
    </button>
  </div>
</div>
