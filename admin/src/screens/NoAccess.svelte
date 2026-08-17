<script lang="ts">
  /**
   * A valid Google sign-in with no row in `admin_users`. A screen rather than a redirect: bouncing
   * someone back to the sign-in button reads as a broken login and they would try forever.
   *
   * It says nothing about who does have access, and the server logged no email when it refused. This
   * page is reachable by anyone who finds the subdomain and signs in, so it is a page for strangers.
   */
  import Wordmark from '../components/Wordmark.svelte';
  import { session } from '../lib/session.svelte.ts';
</script>

<div class="adm-gate">
  <Wordmark label="Evenly Admin" />
  <h1>No access</h1>
  <p>
    You are signed in, but this account is not on the admin list. Nothing was loaded. If that is
    wrong, ask whoever runs Evenly to add you.
  </p>
  <div class="adm-actions">
    <button onclick={() => session.signOut()}>Sign out</button>
  </div>
</div>
