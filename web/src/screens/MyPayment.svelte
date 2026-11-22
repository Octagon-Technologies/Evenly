<script lang="ts">
  /**
   * Paying back on this bill, and fixing what you recorded (`docs/features/one-time-splits.md` B4,
   * mockup 11). The guest records what they paid the payer; the same browser keeps the session, so
   * reopening the link lets her change or remove it. The person owed corrects it from the app.
   *
   * This is one bill's payment, not settle-up: no balances and no other bills (`web/AGENTS.md`). No
   * optimistic write either: the card shows what the server returned.
   */
  import { onMount } from 'svelte';
  import { amount, money, parseAmount, paymentAppName } from '../lib/format.ts';
  import type { ClaimStore } from '../lib/store.svelte.ts';

  const { store }: { store: ClaimStore } = $props();

  const payerName = $derived(store.bill?.payer.name ?? 'the payer');
  const latest = $derived(store.myPayments?.payments[0] ?? null);
  const remaining = $derived(store.myPayments?.remainingSubunits ?? store.myTotalSubunits);

  let open = $state(false);
  let text = $state('');
  let problem = $state<string | null>(null);
  // Removing puts the money back as owed, so it takes a second tap.
  let confirmRemove = $state(false);

  onMount(() => {
    void store.loadPayments();
  });

  function time(ms: number): string {
    return new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  }

  function startRecording() {
    text = amount(remaining, store.currency);
    problem = null;
    open = true;
  }

  function cancel() {
    open = false;
    confirmRemove = false;
    problem = null;
  }

  function startChanging() {
    if (!latest) return;
    text = amount(latest.hereSubunits, store.currency);
    problem = null;
    open = true;
  }

  async function save() {
    const subunits = parseAmount(text, store.currency);
    if (subunits === null || subunits <= 0) {
      problem = 'Enter what you paid.';
      return;
    }
    problem = latest ? await store.changePayment(latest.id, subunits) : await store.pay(subunits, null);
    if (problem === null) open = false;
  }

  async function remove() {
    if (!latest) return;
    if (!confirmRemove) {
      confirmRemove = true;
      return;
    }
    confirmRemove = false;
    problem = await store.changePayment(latest.id, 0);
    if (problem === null) open = false;
  }
</script>

{#if latest}
  <div class="card mp">
    <div class="mp-k">{open ? 'Change what you paid' : 'You paid'}</div>
    <div class="mp-sub">
      You recorded {money(latest.hereSubunits, store.currency)}{latest.app ? ` with ${paymentAppName(latest.app)}` : ''}
      at {time(latest.settledAt)}.
    </div>
    {#if open}
      <input class="inp mono" type="text" inputmode="decimal" aria-label="What you paid" bind:value={text} />
      {#if problem}<div class="note note--amber" role="alert">{problem}</div>{/if}
      <button type="button" class="btn" disabled={store.busy} onclick={save}>Save</button>
      <button type="button" class="link mp-remove" disabled={store.busy} onclick={remove}>
        {confirmRemove ? `Remove this ${money(latest.hereSubunits, store.currency)} payment` : "I didn't pay this"}
      </button>
      <button type="button" class="link" onclick={cancel}>Cancel</button>
    {:else}
      <button type="button" class="link" onclick={startChanging}>Change what you paid</button>
    {/if}
  </div>
{:else if remaining > 0}
  {#if open}
    <div class="card mp">
      <div class="mp-k">You paid {payerName}</div>
      <input class="inp mono" type="text" inputmode="decimal" aria-label="What you paid" bind:value={text} />
      <div class="mp-sub">of {money(remaining, store.currency)}</div>
      {#if problem}<div class="note note--amber" role="alert">{problem}</div>{/if}
      <button type="button" class="btn" disabled={store.busy} onclick={save}>Save</button>
      <button type="button" class="link" onclick={cancel}>Cancel</button>
    </div>
  {:else}
    <button type="button" class="btn btn--ghost" onclick={startRecording}>I paid {payerName}</button>
  {/if}
{/if}

<style>
  .mp {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 16px;
  }
  .mp-k {
    font-weight: 600;
    color: var(--ink);
  }
  .mp-sub {
    color: var(--ink-2);
    font-size: 13px;
  }
  .mp-remove {
    align-self: center;
    color: var(--danger, var(--ink-2));
  }
</style>
