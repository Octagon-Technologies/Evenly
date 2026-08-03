<script lang="ts">
  /**
   * Frame 3 — the session token did the work (spec §2.2).
   *
   * E5, the most likely real failure, is one phone passed around a table. So the session is
   * **offered, never assumed**: this screen leads with "Claim my items" but keeps "Not you?" at equal
   * prominence, one tap away, on the way in rather than three screens later.
   */
  import BillHeader from '../components/BillHeader.svelte';
  import ErrorBanner from '../components/ErrorBanner.svelte';
  import InstallLine from '../components/InstallLine.svelte';
  import type { ClaimStore } from '../lib/store.svelte.ts';

  const { store }: { store: ClaimStore } = $props();
</script>

<div class="page">
  <div class="scroll">
    {#if store.header}
      <BillHeader
        header={store.header}
        title="Welcome back, {store.identity?.name ?? 'you'}"
        sub="This phone remembers you, so there's nothing to set up. Straight to the food."
      />
    {/if}

    {#if store.error}
      <ErrorBanner message={store.error} />
    {/if}

    <button type="button" class="btn" aria-busy={store.busy} onclick={() => store.enterClaiming()}>
      Claim my items
    </button>

    <div class="spacer"></div>

    <button type="button" class="btn btn--ghost btn--sm" onclick={() => store.release()}>
      Not {store.identity?.name ?? 'you'}? Use a different name
    </button>

    <InstallLine benefit="Skip typing your name on every bill." />
  </div>
</div>
