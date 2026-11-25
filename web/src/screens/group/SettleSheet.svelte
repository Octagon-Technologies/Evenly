<script lang="ts">
  /**
   * "Settle up" from the group page: pick who, confirm how much, record it. The money moves however the
   * two of them usually pay; this only records it, oldest bill first, never more than is owed
   * (`record_web_group_payment`). The person paid gets a push and can remove it from the app.
   */
  import { untrack } from 'svelte';
  import Sheet from '../../components/Sheet.svelte';
  import { amount, money, parseAmount } from '../../lib/format.ts';
  import type { GroupStore } from '../../lib/group.svelte.ts';
  import type { Debt } from '../../lib/money/balances.ts';
  import type { ProofFile } from '../../lib/api.ts';
  import ProofPicker from '../../components/ProofPicker.svelte';

  interface Props {
    store: GroupStore;
    onclose: () => void;
    ondone: (message: string) => void;
    /** Paying back one expense instead of the whole balance: the debt is that bill's remainder. */
    forExpense?: { id: string; title: string; debt: Debt };
  }

  const { store, onclose, ondone, forExpense }: Props = $props();

  const debts = $derived(forExpense ? [forExpense.debt] : store.debts.iOwe);
  // Seeded once when the sheet opens: with one person owed, they are already picked.
  const first = untrack(() =>
    forExpense ? forExpense.debt : store.debts.iOwe.length === 1 ? store.debts.iOwe[0] : null,
  );
  let picked = $state<Debt | null>(first);
  let text = $state(first ? amount(first.amountSubunits, first.currency) : '');
  let problem = $state<string | null>(null);
  let proof = $state<ProofFile | null>(null);

  function pick(d: Debt) {
    picked = d;
    text = amount(d.amountSubunits, d.currency);
    problem = null;
  }

  async function save() {
    if (!picked) {
      problem = 'Pick who you paid.';
      return;
    }
    const subunits = parseAmount(text, picked.currency);
    if (subunits === null || subunits <= 0) {
      problem = 'Enter what you paid.';
      return;
    }
    if (subunits > picked.amountSubunits) {
      problem = `That's more than you owe ${store.name(picked.creditorUserId)} (${money(picked.amountSubunits, picked.currency)}).`;
      return;
    }
    const to = store.name(picked.creditorUserId);
    const res = await store.pay(picked, subunits, forExpense?.id ?? null, proof);
    if (res) problem = res;
    else ondone(`Recorded. ${to} will see it.`);
  }
</script>

<Sheet title="Pay back" {onclose}>
  {#if picked && debts.length === 1}
    <div class="h2">Pay {store.name(picked.creditorUserId)} back{forExpense ? ` for ${forExpense.title}` : ''}</div>
  {:else}
    <div class="h2">Who did you pay?</div>
    <div class="chips" style="margin-bottom:12px">
      {#each debts as d (d.creditorUserId + d.currency)}
        <button type="button" class="chip chip--pick" class:chip--picked={picked === d} onclick={() => pick(d)}>
          {store.name(d.creditorUserId)} · {money(d.amountSubunits, d.currency)}
        </button>
      {/each}
    </div>
  {/if}
  <div class="sub" style="margin-bottom:14px">
    Send it however you two usually do, then record it here so everyone's numbers update.
  </div>
  {#if picked}
    <div class="field">
      <div class="flbl">Amount</div>
      <input class="inp mono" type="text" inputmode="decimal" aria-label="What you paid" bind:value={text} />
    </div>
  {/if}
  {#if picked}
    <ProofPicker otherName={store.name(picked.creditorUserId)} value={proof} onchange={(f) => (proof = f)} />
  {/if}
  {#if problem}<div class="note note--amber" role="alert">{problem}</div>{/if}
  <button type="button" class="btn" disabled={store.busy} onclick={save}>
    {picked ? `I paid ${store.name(picked.creditorUserId)}` : 'Record payment'}
  </button>
  <p class="sub center" style="margin-top:10px">
    {picked ? `${store.name(picked.creditorUserId)} gets` : 'They get'} a notification and can say if it didn't arrive.
  </p>
</Sheet>
