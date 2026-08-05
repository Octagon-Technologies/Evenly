<script lang="ts">
  /**
   * Frame 1 — recognition, not a search box (spec §2.3 tier 2).
   *
   * Asking a returning guest to find herself in a list of names is worse than asking her to type, so
   * she will type, and we get a duplicate. A name is unanswerable; a name plus what it spent money on
   * is answerable in a second — which is why every candidate carries up to two real expenses.
   *
   * Only **unclaimed placeholders** are ever listed (§4.4). Real accounts are never selectable: if
   * they were, anyone with the link could tap "I'm Andrew" and spend Andrew's money.
   */
  import BillHeader from '../components/BillHeader.svelte';
  import ErrorBanner from '../components/ErrorBanner.svelte';
  import { money, shortDate } from '../lib/format.ts';
  import type { ClaimStore } from '../lib/store.svelte.ts';

  const { store }: { store: ClaimStore } = $props();

  let picking = $state<string | null>(null);

  async function pick(userId: string) {
    picking = userId;
    const { won } = await store.claimPlaceholder(userId);
    picking = null;
    if (won) await store.enterClaiming();
  }
</script>

<div class="page">
  <div class="scroll">
    {#if store.header}
      <BillHeader header={store.header} title="Who are you?" />
    {/if}

    <div class="note note--blue">
      <b>Been in this group before?</b> Tap your name so your old expenses stay with you.
    </div>

    {#if store.error}
      <ErrorBanner message={store.error} />
    {/if}

    <div class="card">
      {#each store.candidates as candidate (candidate.userId)}
        <div class="row">
          <div class="grow">
            <div class="lbl">{candidate.name}</div>
            {#if candidate.evidence.length > 0}
              <div class="ev">
                {#each candidate.evidence as e (e.title + e.date)}
                  <div class="evi">
                    <div class="t">{e.title}</div>
                    <div class="d">
                      {shortDate(e.date)} · {money(e.amountSubunits, store.currency)}
                    </div>
                  </div>
                {/each}
              </div>
            {:else}
              <div class="meta">Added to the group, nothing claimed yet</div>
            {/if}
          </div>
          <button
            type="button"
            class="act"
            aria-busy={picking === candidate.userId}
            onclick={() => pick(candidate.userId)}
          >
            {picking === candidate.userId ? 'Just a sec…' : "That's me"}
          </button>
        </div>
      {/each}
    </div>

    <button type="button" class="btn btn--ghost btn--sm" onclick={() => (store.phase = 'name_entry')}>
      I'm none of these, I'm new
    </button>

    <!-- §4.4: if you have the app, use the app. A web guest may never act as an account holder. -->
    <p class="sub center" style="margin-top:16px">
      Have Evenly? Open the bill there instead.
    </p>
  </div>
</div>
