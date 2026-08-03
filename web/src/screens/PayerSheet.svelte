<script lang="ts">
  /**
   * "Who actually paid?" (spec §3.8, §5.5, E24, E25; the frame in `design/web-claim-mockup.html`).
   *
   * Common when the person with the app is not the person whose card went down: Andrew scans the
   * receipt because he has Evenly, Jane actually paid. A guest may be **named payer of an existing
   * bill**; a guest may never create one, upload a receipt, or trigger OCR (spec §9), and nothing here
   * comes close to any of that.
   *
   * The payer field is Zone 2, so this is a causal write, not a field update. The page hands back the
   * `split_version` it last read and the server decides against it. Two guests both tapping this is
   * E25: the second one is stale, loses, and is *told* who the payer is. That is the one genuinely
   * awkward state in this feature, so it gets a sentence of its own rather than a generic error.
   */
  import Sheet from '../components/Sheet.svelte';
  import Avatar from '../components/Avatar.svelte';
  import { money } from '../lib/format.ts';
  import type { ClaimStore } from '../lib/store.svelte.ts';

  interface Props {
    store: ClaimStore;
    onclose: () => void;
  }

  const { store, onclose }: Props = $props();

  const currentPayerId = $derived(store.bill?.payer.userId ?? null);
  /** Chosen but not yet sent, so the consequence can be stated before anything moves. */
  let picked = $state<string | null>(null);
  const chosen = $derived(picked ?? currentPayerId);
  const billTotal = $derived(store.header?.totalSubunits ?? 0);
  const others = $derived(Math.max(store.peopleOnBill.length - 1, 0));

  const chosenName = $derived(
    store.peopleOnBill.find((p) => p.userId === chosen)?.name ?? 'Nobody',
  );
  const chosenIsMe = $derived(Boolean(chosen && chosen === store.identity?.userId));

  async function confirm() {
    if (!chosen || chosen === currentPayerId) {
      onclose();
      return;
    }
    // A loss (E25) is not a failure to retry: the sheet closes and the notice explains it on the page
    // behind, next to the amount it changes.
    await store.setPayer(chosen);
    onclose();
  }
</script>

<Sheet title="Who actually paid?" {onclose}>
  <div class="h2">Who actually paid?</div>
  <p class="sub" style="margin-bottom:13px">
    {store.bill?.payer.name ?? 'Nobody'} is down as the payer. Change it if that's wrong.
  </p>

  <div class="card">
    {#each store.peopleOnBill as person (person.userId)}
      {@const isMe = person.userId === store.identity?.userId}
      <div class="prow" class:prow--mine={isMe}>
        <span class="chip"><Avatar name={person.name} /></span>
        <div class="grow">
          <div class="lbl">
            {person.name}{#if isMe}<b style="color:var(--blue);font-weight:650">&nbsp;· you</b>{/if}
          </div>
          {#if person.userId === currentPayerId}
            <div class="meta">Down as the payer now</div>
          {:else if isMe}
            <div class="meta">Everyone will owe you</div>
          {/if}
        </div>
        <button
          type="button"
          class="act"
          class:act--on={person.userId === chosen}
          onclick={() => (picked = person.userId)}
        >
          {person.userId === chosen ? 'Picked' : 'Pick'}
        </button>
      </div>
    {/each}
  </div>

  {#if chosenIsMe}
    <div class="note note--green">
      <b>You paid <span class="mono">{money(billTotal, store.currency)}</span>.</b>
      The other {others} owe you their share. {store.bill?.payer.name ?? 'The payer'} sees this in the app.
    </div>
  {:else if chosen !== currentPayerId}
    <div class="note note--blue">
      {chosenName} paid <span class="mono">{money(billTotal, store.currency)}</span>. Everyone, you included,
      owes them their share.
    </div>
  {/if}

  {#if store.error}
    <div class="note note--red" role="alert">{store.error}</div>
  {/if}

  <button type="button" class="btn" aria-busy={store.busy} onclick={confirm}>That's right</button>
</Sheet>

<style>
  .prow {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 10px 12px;
    border-top: 1px solid var(--border);
  }
  .prow:first-child {
    border-top: none;
  }
  .prow--mine {
    background: var(--blue-tint);
  }
</style>
