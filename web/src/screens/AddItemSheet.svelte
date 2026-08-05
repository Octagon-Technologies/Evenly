<script lang="ts">
  /**
   * Frame 7 — adding what the scan missed (spec §3.6).
   *
   * Adding a line changes the bill total and therefore **everyone's** money, so it applies straight
   * away and is **announced** rather than held for approval (§2.7). This sheet is honest about that
   * before the commit: it shows the effect on the bill total, and it says the payer will be told. What
   * it does not do is make anyone wait, which is the whole point.
   */
  import Sheet from '../components/Sheet.svelte';
  import { money, parseAmount } from '../lib/format.ts';
  import type { ClaimStore } from '../lib/store.svelte.ts';

  interface Props {
    store: ClaimStore;
    onclose: () => void;
  }

  const { store, onclose }: Props = $props();

  let label = $state('');
  let quantity = $state('1');
  let priceEach = $state('');
  let hint = $state<string | null>(null);

  const qty = $derived(Math.max(1, Math.trunc(Number(quantity) || 0)));
  const unitPriceSubunits = $derived(parseAmount(priceEach, store.currency));
  /** The bill's own total, extras included — the number the payer will compare against. */
  const billTotal = $derived(store.header?.totalSubunits ?? 0);
  const newTotal = $derived(billTotal + (unitPriceSubunits ?? 0) * qty);
  const ready = $derived(label.trim().length > 0 && unitPriceSubunits !== null && unitPriceSubunits > 0);

  async function submit() {
    if (!ready) {
      // Never grey out a control with no explanation (root AGENTS.md §7).
      hint =
        label.trim().length === 0
          ? 'Give it a name first, so everyone knows what it was.'
          : 'Add what it cost, so it can be split.';
      return;
    }
    hint = null;
    const ok = await store.editLine({
      kind: 'ADD',
      label: label.trim(),
      quantity: qty,
      unitPriceSubunits: unitPriceSubunits ?? 0,
    });
    if (ok) onclose();
  }
</script>

<Sheet title="Add what's missing" {onclose}>
  <div class="h2">Add what's missing</div>
  <p class="sub" style="margin-bottom:13px">The scan missed it? Add it and claim it.</p>

  <div class="field">
    <label class="flbl" for="add-label">What was it?</label>
    <input id="add-label" class="inp" type="text" maxlength="80" bind:value={label} />
  </div>

  <div class="fieldrow">
    <div style="flex:1">
      <label class="flbl" for="add-qty">How many?</label>
      <input id="add-qty" class="inp" type="number" inputmode="numeric" min="1" bind:value={quantity} />
    </div>
    <div style="flex:1.3">
      <label class="flbl" for="add-price">Price each</label>
      <input
        id="add-price"
        class="inp mono"
        type="text"
        inputmode="decimal"
        placeholder={money(0, store.currency)}
        bind:value={priceEach}
      />
    </div>
  </div>

  <div class="note note--amber">
    <b>This changes the bill total.</b>
    <span class="mono">{money(billTotal, store.currency)}</span> →
    <span class="mono">{money(newTotal, store.currency)}</span>.
    Everyone on the bill sees it, and anyone can undo it.
  </div>

  {#if hint}
    <div class="note note--amber" role="alert">{hint}</div>
  {/if}
  {#if store.error}
    <div class="note note--red" role="alert">{store.error}</div>
  {/if}

  <button type="button" class="btn" class:btn--off={!ready} aria-busy={store.busy} onclick={submit}>
    Add and claim it
  </button>
</Sheet>
