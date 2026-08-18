<script lang="ts">
  /**
   * The shell. Four terminal states and one working one, and the important thing about them is that
   * `ready` is reached only after the SERVER said so: `session.boot()` calls the `admin` function's
   * `me` action, which verifies the JWT and checks `admin_users` before answering (spec §2.3).
   *
   * There is no client-side allowlist and no `isAdmin` boolean in this bundle to tamper with. Editing
   * anything here in a devtools console gets you a different set of pixels and no data.
   *
   * Only the waitlist is built (build order steps 1 to 3). The feedback queue is step 7 and is not
   * stubbed in the nav: a tab that leads nowhere is worse than one that is not there yet.
   */
  import SignIn from './screens/SignIn.svelte';
  import NoAccess from './screens/NoAccess.svelte';
  import Waitlist from './screens/Waitlist.svelte';
  import Wordmark from './components/Wordmark.svelte';
  import { session } from './lib/session.svelte.ts';

  void session.boot();
</script>

{#if session.phase === 'loading'}
  <div class="adm-gate"><div class="adm-spin"></div></div>
{:else if session.phase === 'unconfigured'}
  <div class="adm-gate">
    <Wordmark label="Evenly Admin" />
    <h1>Not configured</h1>
    <p>
      This deploy is missing its environment variables. Set <code>VITE_SUPABASE_URL</code>,
      <code>VITE_SUPABASE_ANON_KEY</code> and <code>VITE_ADMIN_FN_URL</code>, then redeploy. See
      <code>admin/README.md</code>.
    </p>
  </div>
{:else if session.phase === 'signed_out'}
  <SignIn />
{:else if session.phase === 'no_access'}
  <NoAccess />
{:else if session.phase === 'error'}
  <div class="adm-gate">
    <Wordmark label="Evenly Admin" />
    <h1>Something went wrong</h1>
    <p>{session.error}</p>
    <div class="adm-actions">
      <button class="adm-primary" onclick={() => location.reload()}>Try again</button>
      <button class="adm-quiet" onclick={() => session.signOut()}>Sign out</button>
    </div>
  </div>
{:else}
  <header class="adm-bar">
    <div style="display:flex;align-items:center;gap:10px">
      <Wordmark />
      <span class="adm-pill">Admin</span>
    </div>
    <div class="adm-who">
      <span>{session.email}</span>
      <button class="adm-quiet" onclick={() => session.signOut()}>Sign out</button>
    </div>
  </header>

  <main class="adm-main">
    <Waitlist />
  </main>
{/if}
