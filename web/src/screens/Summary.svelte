<script lang="ts">
  /**
   * Frame 8 — the number they came for (spec §3.7).
   *
   * **Tax and tip are itemised, not buried.** "$4.50 juice, $34.20 total" reads as a bug unless the
   * extras are broken out, and the wording mirrors the app exactly: tax is a share of what you ate,
   * tip splits evenly.
   *
   * `I'm done` is a nudge-silencer, not a lock (§3.3, E31), so `Change what I claimed` stays live to
   * the end and simply goes back to the list.
   */
  import InstallLine from '../components/InstallLine.svelte';
  import ErrorBanner from '../components/ErrorBanner.svelte';
  import PayerSheet from './PayerSheet.svelte';
  import { money, paymentAppName } from '../lib/format.ts';
  import type { ClaimStore } from '../lib/store.svelte.ts';

  const { store }: { store: ClaimStore } = $props();

  let copied = $state(false);
  let pickingPayer = $state(false);

  const myLines = $derived(store.lines.filter((l) => l.mine && l.myShareSubunits > 0));
  const payerName = $derived(store.bill?.payer.name ?? 'the payer');

  /** Who else is on a line with me, so "shared with Mary" can be stated rather than implied. */
  function sharedWith(itemId: string): string {
    const line = store.lines.find((l) => l.itemId === itemId);
    const others = (line?.chips ?? []).filter((c) => !c.isMe).map((c) => c.name);
    if (others.length === 0) return '';
    if (others.length === 1) return ` · shared with ${others[0]}`;
    if (others.length === 2) return ` · shared with ${others[0]} and ${others[1]}`;
    return ` · shared with ${others.length} others`;
  }

  async function copyHandle() {
    const handle = store.bill?.payer.handle;
    if (!handle) return;
    try {
      await navigator.clipboard.writeText(handle);
      copied = true;
      setTimeout(() => (copied = false), 2000);
    } catch {
      copied = false;
    }
  }
</script>

<div class="page">
  <div class="scroll">
    <div class="hdr" style="padding-bottom:10px">
      <div class="h2">You're done, {store.identity?.name ?? 'you'}</div>
    </div>

    <div class="hero">
      <!-- Naming yourself the payer flips what this number means, so it has to flip too: the same
           amount is what you owe when someone else paid, and your own share of what you laid out
           when you did (spec §3.8). -->
      <div class="k">{store.iAmPayer ? 'Your share of what you paid' : `You owe ${payerName}`}</div>
      <div class="v mono">{money(store.myTotalSubunits, store.currency)}</div>
      <div class="w">{store.header?.groupName} · {store.header?.title}</div>
    </div>

    {#if store.payerNotice}
      <!-- E25, the one genuinely awkward state: she tapped "I paid" and lost the causal race. Said
           plainly, and immediately reassuring about the part she cares about. -->
      <div class="note note--amber" role="alert">{store.payerNotice}</div>
    {/if}

    {#if store.error}
      <ErrorBanner message={store.error} onretry={() => store.refresh()} />
    {/if}

    <div class="card card--tot">
      {#each myLines as line (line.itemId)}
        <div class="tot">
          <span>
            {line.label}<b style="color:var(--ink-2);font-weight:500">{sharedWith(line.itemId)}</b>
          </span>
          <b class="mono">{money(line.myShareSubunits, store.currency)}</b>
        </div>
      {/each}

      {#if store.myBreakdown && store.myBreakdown.taxSubunits !== 0}
        <div class="tot tot--extra">
          <span>Tax <b style="font-weight:500">· share of what you ate</b></span>
          <b class="mono">{money(store.myBreakdown.taxSubunits, store.currency)}</b>
        </div>
      {/if}
      {#if store.myBreakdown && store.myBreakdown.tipSubunits !== 0}
        <div class="tot tot--extra">
          <span>Tip <b style="font-weight:500">· split evenly</b></span>
          <b class="mono">{money(store.myBreakdown.tipSubunits, store.currency)}</b>
        </div>
      {/if}
      {#if store.myBreakdown && store.myBreakdown.discountSubunits !== 0}
        <div class="tot tot--extra">
          <span>Discount <b style="font-weight:500">· share of what you ate</b></span>
          <b class="mono">−{money(store.myBreakdown.discountSubunits, store.currency)}</b>
        </div>
      {/if}

      <div class="tot tot--big">
        <span>Your total</span>
        <span class="mono">{money(store.myTotalSubunits, store.currency)}</span>
      </div>
    </div>

    {#if store.iAmPayer}
      <div class="note note--green">
        You paid for this one. The other {Math.max(store.peopleOnBill.length - 1, 0)} owe you their share.
      </div>
    {:else if store.bill?.payer.handle}
      <div class="pay">
        <div class="ic" aria-hidden="true">{(store.bill.payer.app ?? '?')[0].toUpperCase()}</div>
        <div class="grow">
          <div class="lbl">Pay {payerName} on {paymentAppName(store.bill.payer.app)}</div>
          <div class="meta">{store.bill.payer.handle}</div>
        </div>
        <button type="button" class="act" onclick={copyHandle}>{copied ? 'Copied' : 'Copy'}</button>
      </div>
    {:else}
      <div class="note note--blue">
        {payerName} hasn't added a way to pay them yet. Settle it with them directly.
      </div>
    {/if}

    <!-- Whoever holds the app is usually not whoever's card went down (§3.8). This lives here rather
         than on the claim list because this is the screen where "who do I pay" is the question. -->
    <p class="appline">
      {store.iAmPayer ? 'Not you who paid?' : `${payerName} didn't pay?`}
      <button type="button" class="link" onclick={() => (pickingPayer = true)}>Change who paid</button>
    </p>

    <button type="button" class="btn btn--ghost btn--sm" onclick={() => store.setDone(false)}>
      Change what I claimed
    </button>

    <InstallLine benefit="Keep every bill you've split, in one place." />
  </div>
</div>

{#if pickingPayer}
  <PayerSheet {store} onclose={() => (pickingPayer = false)} />
{/if}
