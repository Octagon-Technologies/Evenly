<script lang="ts">
  /**
   * The route table: the claim flow (spec §3.1, one URL `/b/:token` and a phase the store decides)
   * plus the marketing site that now lives alongside it — home, privacy, terms. `resolveRoute` picks
   * between them once, from `window.location`; a bill token always wins, so a malformed claim link
   * never lands on the marketing home page believing it's a fresh visit.
   */
  import PickName from './screens/PickName.svelte';
  import NameEntry from './screens/NameEntry.svelte';
  import WelcomeBack from './screens/WelcomeBack.svelte';
  import ClaimList from './screens/ClaimList.svelte';
  import Summary from './screens/Summary.svelte';
  import Expired from './screens/Expired.svelte';
  import Gone from './screens/Gone.svelte';
  import Home from './marketing/Home.svelte';
  import Privacy from './marketing/Privacy.svelte';
  import Terms from './marketing/Terms.svelte';
  import { store } from './lib/store.svelte.ts';
  import { resolveRoute } from './lib/router.ts';
  import { onMount } from 'svelte';

  const route = resolveRoute();

  const TITLES = {
    home: 'Evenly — Split the bill, not the friendship.',
    privacy: 'Privacy Policy · Evenly',
    terms: 'Terms of Service · Evenly',
    bill: 'Claim your items · Evenly',
  } as const;

  onMount(() => {
    document.title = TITLES[route.kind];
    if (route.kind === 'bill') {
      const meta = document.createElement('meta');
      meta.name = 'robots';
      meta.content = 'noindex, nofollow';
      document.head.appendChild(meta);
    }
  });
</script>

{#if route.kind === 'home'}
  <Home />
{:else if route.kind === 'privacy'}
  <Privacy />
{:else if route.kind === 'terms'}
  <Terms />
{:else}
  <div class="claim-shell">
    {#if store.phase === 'loading'}
      <div class="page">
        <div class="scroll center" style="padding-top:80px">
          <div class="spin" style="margin:0 auto 14px"></div>
          <p class="sub">Opening the bill…</p>
        </div>
      </div>
    {:else if store.phase === 'pick_name'}
      <PickName {store} />
    {:else if store.phase === 'name_entry'}
      <NameEntry {store} />
    {:else if store.phase === 'welcome_back'}
      <WelcomeBack {store} />
    {:else if store.phase === 'claiming'}
      <ClaimList {store} />
    {:else if store.phase === 'summary'}
      <Summary {store} />
    {:else if store.phase === 'expired'}
      <Expired {store} />
    {:else if store.phase === 'claimed_elsewhere'}
      <Gone reason="claimed_elsewhere" name={store.identity?.name} />
    {:else}
      <Gone reason={store.phase === 'no_token' ? 'no_token' : 'gone'} />
    {/if}
  </div>
{/if}
