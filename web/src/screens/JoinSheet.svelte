<script lang="ts">
  /**
   * Frame 5 — joining a line someone else already claimed.
   *
   * It gets its own sheet because it changes **someone else's** money, and §3.5 requires the
   * consequence to be stated before the commit, not discovered after: Mary was paying $18.00, now
   * $9.00. Joining still applies **instantly** and is attributed — only *editing* a line needs the
   * payer's approval (§2.7), and these two look inconsistent on purpose. Do not harmonise them.
   */
  import Sheet from '../components/Sheet.svelte';
  import Avatar from '../components/Avatar.svelte';
  import { money } from '../lib/format.ts';
  import type { LineView } from '../lib/lines.ts';
  import type { ClaimStore } from '../lib/store.svelte.ts';

  interface Props {
    store: ClaimStore;
    line: LineView;
    onclose: () => void;
  }

  const { store, line, onclose }: Props = $props();

  let overClaimed = $state(false);

  /**
   * What each person on the line pays now, and what they will pay once I join. Computed the same way
   * the engine does — the line total split evenly across the people on it — rather than guessed.
   */
  const holders = $derived(line.chips.filter((c) => !c.isMe));
  const perPersonNow = $derived(
    holders.length > 0 ? Math.round(line.lineTotalSubunits / Math.max(line.assigned, 1) / holders.length) : 0,
  );
  const beforeEach = $derived(
    holders.length > 0 ? Math.round(line.lineTotalSubunits / holders.length) : line.lineTotalSubunits,
  );
  const afterEach = $derived(Math.round(line.lineTotalSubunits / (holders.length + 1)));

  async function join() {
    const result = await store.join(line.itemId, line.chips.find((c) => c.portionId)?.portionId ?? null, overClaimed);
    if (result === 'overclaimed') {
      // E14: the last unit went while this sheet was open. Not an error — an offer to add anyway.
      overClaimed = true;
      return;
    }
    onclose();
  }
</script>

<Sheet title="Join {line.label}" {onclose}>
  <div class="h2">{line.label} · <span class="mono">{money(line.lineTotalSubunits, store.currency)}</span></div>
  <p class="sub" style="margin-bottom:13px">
    {#if holders.length === 1}
      {holders[0].name} claimed this one alone. Joining splits it between you.
    {:else}
      {holders.length} people are on this one. Joining splits it between all of you.
    {/if}
  </p>

  <div class="card">
    {#each holders as holder (holder.userId + (holder.portionId ?? ''))}
      <div class="row row--center">
        <div class="grow">
          <div class="lbl">{holder.name}</div>
          <div class="meta">
            Was paying <span class="mono">{money(beforeEach, store.currency)}</span> · now
            <span class="mono">{money(afterEach, store.currency)}</span>
          </div>
        </div>
        <span class="chip"><Avatar name={holder.name} /></span>
      </div>
    {/each}
    <div class="row row--mine row--center">
      <div class="grow">
        <div class="lbl">You</div>
        <div class="meta">You'll pay <span class="mono">{money(afterEach, store.currency)}</span></div>
      </div>
      <span class="chip chip--me"><Avatar name={store.identity?.name ?? 'You'} /></span>
    </div>
  </div>

  {#if overClaimed}
    <!-- Over-claim is surfaced, never silently capped (§2.5, E15). -->
    <div class="note note--amber" role="alert">
      <b>That's more than were ordered.</b>
      There are {line.quantity} of these on the bill and they're all spoken for. Add yourself anyway and
      {store.bill?.payer.name ?? 'the payer'} will see it flagged, or close this and pick something else.
    </div>
  {:else}
    <div class="note note--amber">
      {holders.length === 1 ? holders[0].name : 'They'} will see that you joined, and can take
      {holders.length === 1 ? 'herself' : 'themselves'} off if you've got it wrong.
    </div>
  {/if}

  {#if store.error}
    <div class="note note--red" role="alert">{store.error}</div>
  {/if}

  <button type="button" class="btn" aria-busy={store.busy} onclick={join}>
    {overClaimed ? 'Add me anyway' : 'Join this plate'}
  </button>
</Sheet>
