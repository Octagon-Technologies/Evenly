<script lang="ts">
  /**
   * "Settle up" from the group page: pick who, confirm how much, record it. The money moves however the
   * two of them usually pay; this only records it, oldest bill first, never more than is owed
   * (`record_web_group_payment`). The person paid gets a push and can remove it from the app.
   *
   * Opened with a debt owed TO the guest, it records the other direction: "Bob paid me $20" against one
   * bill the guest paid for. Any amount up to what is left is fine, so a partial payment is just a number.
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
    /** One expense instead of the whole balance: the debt is that bill's remainder, either direction. */
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
  // "They paid me": the guest is the one owed, so the other person is the debtor.
  const received = $derived(!!picked && picked.creditorUserId === store.me);
  const other = $derived(picked ? store.name(received ? picked.debtorUserId : picked.creditorUserId) : '');

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
      problem = received ? `Enter what ${other} paid you.` : 'Enter what you paid.';
      return;
    }
    if (subunits > picked.amountSubunits) {
      problem = received
        ? `That's more than ${other} owes you here (${money(picked.amountSubunits, picked.currency)}).`
        : `That's more than you owe ${other} (${money(picked.amountSubunits, picked.currency)}).`;
      return;
    }
    const who = other;
    const res = await store.pay(picked, subunits, forExpense?.id ?? null, proof);
    if (res) problem = res;
    else ondone(`Recorded. ${who} will see it.`);
  }
</script>

<Sheet title={received ? 'Record a payment' : 'Pay back'} {onclose}>
  {#if picked && debts.length === 1 && received}
    <div class="h2">What did {other} pay you{forExpense ? ` for ${forExpense.title}` : ''}?</div>
  {:else if picked && debts.length === 1}
    <div class="h2">Pay {other} back{forExpense ? ` for ${forExpense.title}` : ''}</div>
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
    {#if received}
      {money(picked?.amountSubunits ?? 0, picked?.currency ?? 'USD')} is left. Paid part of it? Enter just what you got.
    {:else}
      Send it however you two usually do, then record it here so everyone's numbers update.
    {/if}
  </div>
  {#if picked}
    <div class="field">
      <div class="flbl">Amount</div>
      <input class="inp mono" type="text" inputmode="decimal" aria-label={received ? `What ${other} paid you` : 'What you paid'} bind:value={text} />
    </div>
  {/if}
  {#if picked}
    <ProofPicker otherName={other} value={proof} onchange={(f) => (proof = f)} />
  {/if}
  {#if problem}<div class="note note--amber" role="alert">{problem}</div>{/if}
  <button type="button" class="btn" disabled={store.busy} onclick={save}>
    {picked ? (received ? `${other} paid me` : `I paid ${other}`) : 'Record payment'}
  </button>
  <p class="sub center" style="margin-top:10px">
    {#if received}
      {other} gets a notification if they use the app.
    {:else}
      {picked ? `${other} gets` : 'They get'} a notification and can say if it didn't arrive.
    {/if}
  </p>
</Sheet>
