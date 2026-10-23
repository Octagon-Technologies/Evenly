<script lang="ts">
  /**
   * "Are any of these you?" on the invite page: the bill flow's recognition list (spec §2.3), for a group.
   *
   * Someone may already have added this friend by name. Picking that name keeps their expenses with them;
   * typing it again would make a duplicate person. Only unclaimed placeholders are listed, never account
   * holders (spec §4.4), so nobody can join as Andrew.
   */
  import ErrorBanner from '../../components/ErrorBanner.svelte';
  import { money, shortDate } from '../../lib/format.ts';
  import type { JoinStore } from '../../lib/join.svelte.ts';

  const { store }: { store: JoinStore } = $props();

  let picking = $state<string | null>(null);

  async function pick(userId: string) {
    picking = userId;
    await store.claim(userId);
    picking = null;
  }
</script>

<div class="page">
  <div class="scroll">
    <div class="hdr">
      <div class="eyebrow">{store.header?.groupName}</div>
      <div class="h1">Are any of these you?</div>
    </div>

    <div class="note note--blue">
      Someone may have added you already. Tap your name so your expenses stay with you.
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
                      {shortDate(e.date)}{e.currency ? ` · ${money(e.amountSubunits, e.currency)}` : ''}
                    </div>
                  </div>
                {/each}
              </div>
            {:else}
              <div class="meta">Added to the group, no expenses yet</div>
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
      I'm not on the list
    </button>
  </div>
</div>
