<script lang="ts">
  /**
   * The whole route table (spec §3.1). One URL, `/b/:token`, and a phase the store decides — there is
   * no navigation to speak of, because there is no web app here: one bill, one job, 72 hours (§9).
   */
  import PickName from './screens/PickName.svelte';
  import NameEntry from './screens/NameEntry.svelte';
  import WelcomeBack from './screens/WelcomeBack.svelte';
  import ClaimList from './screens/ClaimList.svelte';
  import Summary from './screens/Summary.svelte';
  import Expired from './screens/Expired.svelte';
  import Gone from './screens/Gone.svelte';
  import { store } from './lib/store.svelte.ts';
</script>

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
