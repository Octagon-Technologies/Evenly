<script lang="ts">
  /**
   * Frame 9 — expiry without a dead end (spec §3.1, E26).
   *
   * Writes stop; the amount and the way to pay it stay. Paying is the primary action because it is
   * the only thing left worth doing here, and the install line stays a footnote even on the screen
   * where it would convert best (§2.8).
   *
   * If we never got far enough to know a total, this says so rather than showing a confident zero.
   */
  import InstallLine from '../components/InstallLine.svelte';
  import { money, paymentAppName } from '../lib/format.ts';
  import type { ClaimStore } from '../lib/store.svelte.ts';

  const { store }: { store: ClaimStore } = $props();

  const payerName = $derived(store.bill?.payer.name ?? 'whoever paid');
  const knowsTotal = $derived(store.identity !== null && store.bill !== null);
</script>

<div class="page">
  <div class="scroll">
    <div class="hdr" style="padding-bottom:10px">
      <div class="h2">This link has expired</div>
      <div class="sub">Claiming closed 72 hours after the bill was scanned.</div>
    </div>

    {#if knowsTotal}
      <div class="card card--tot">
        <div class="tot">
          <span>{store.header?.groupName} · {store.header?.title}</span>
          <b class="mono">{money(store.progress.totalSubunits, store.currency)}</b>
        </div>
        <div class="tot tot--big">
          <span>You owed {payerName}</span>
          <span class="mono">{money(store.myTotalSubunits, store.currency)}</span>
        </div>
      </div>
    {/if}

    <div class="note note--blue">
      Need to change something? <b>Ask {payerName}</b> to reopen it.
    </div>

    {#if store.bill?.payer.handle}
      <div class="pay">
        <div class="ic" aria-hidden="true">{(store.bill.payer.app ?? '?')[0].toUpperCase()}</div>
        <div class="grow">
          <div class="lbl">Pay {payerName} on {paymentAppName(store.bill.payer.app)}</div>
          <div class="meta">{store.bill.payer.handle}</div>
        </div>
        <button
          type="button"
          class="act"
          onclick={() => navigator.clipboard?.writeText(store.bill!.payer.handle!)}
        >
          Copy
        </button>
      </div>
    {/if}

    <InstallLine benefit="Your bills stay open as long as you need them." />
  </div>
</div>
