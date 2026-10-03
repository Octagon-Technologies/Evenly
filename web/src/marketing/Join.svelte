<script lang="ts">
  /**
   * `/j/:token`, the group invite landing. It names the group when the server will say, and otherwise
   * (unknown token, rate limit, offline, any failure) shows the same page without the name; the
   * fallback is a normal state, not an error. Only the name is ever fetched: no members, no balances.
   * The heading starts generic and swaps once, so a failed lookup causes no visible change.
   */
  import { onMount } from 'svelte';
  import { invitePreview } from '../lib/api.ts';
  import { PRE_LAUNCH, appStoreLink, playStoreLink } from '../lib/format.ts';

  const { token }: { token: string } = $props();
  let groupName = $state<string | null>(null);

  onMount(async () => {
    groupName = await invitePreview(token);
  });
</script>

<main class="join">
  <div class="join-logo">Evenly</div>
  <h1>
    {#if groupName}You're invited to {groupName} on Evenly{:else}You're invited to a group on Evenly{/if}
  </h1>
  <p>Split bills with friends and settle up, without the awkward maths.</p>
  <a class="join-btn" href={`splitevenly://j/${token}`}>Open in Evenly</a>
  <a class="join-btn join-btn-2" href={appStoreLink()}>
    {PRE_LAUNCH ? 'Get early access' : 'Get the app'}
  </a>
  {#if !PRE_LAUNCH}
    <a class="join-link" href={playStoreLink()}>Get it on Google Play</a>
  {/if}
  <small>Opens the app if it is installed.</small>
</main>

<style>
  .join {
    max-width: 380px;
    margin: 12vh auto 0;
    padding: 0 22px;
    text-align: center;
    color: #0b1220;
    font-family: 'IBM Plex Sans', system-ui, sans-serif;
  }
  .join-logo { font-weight: 700; color: #3762e3; font-size: 20px; margin-bottom: 22px; }
  h1 { font-size: 26px; line-height: 1.15; letter-spacing: -0.02em; margin: 0 0 8px; overflow-wrap: anywhere; }
  p { color: #5b6577; margin: 0 0 22px; }
  .join-btn {
    display: flex; align-items: center; justify-content: center; height: 46px; border-radius: 10px;
    background: #3762e3; color: #fff; font-weight: 600; text-decoration: none; margin-bottom: 10px;
  }
  .join-btn-2 { background: #fff; color: #3762e3; border: 1px solid #d6dce6; }
  .join-link { display: block; margin: 4px 0 10px; color: #3762e3; }
  small { color: #9aa3b2; }
</style>
