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
  import Avatar from '../../components/Avatar.svelte';
  import { amount, money, parseAmount, symbol } from '../../lib/format.ts';
  import type { GroupStore } from '../../lib/group.svelte.ts';
  import type { Debt } from '../../lib/money/balances.ts';

  interface Props {
    store: GroupStore;
    onclose: () => void;
    ondone: (message: string) => void;
    /** One expense instead of the whole balance: the debt is that bill's remainder, either direction. */
    forExpense?: { id: string; title: string; debt: Debt };
    /** One person's whole balance, from their card on the Balances tab. Either direction. */
    forDebt?: Debt;
  }

  const { store, onclose, ondone, forExpense, forDebt }: Props = $props();

  const debts = $derived(forExpense ? [forExpense.debt] : forDebt ? [forDebt] : store.debts.iOwe);
  // Seeded once when the sheet opens: with one person owed, they are already picked.
  const first = untrack(() =>
    forExpense
      ? forExpense.debt
      : forDebt
        ? forDebt
        : store.debts.iOwe.length === 1
          ? store.debts.iOwe[0]
          : null,
  );
  let picked = $state<Debt | null>(first);
  let text = $state(first ? amount(first.amountSubunits, first.currency) : '');
  let problem = $state<string | null>(null);
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
    const res = await store.pay(picked, subunits, forExpense?.id ?? null);
    if (res) problem = res;
    else ondone(`Recorded. ${who} will see it.`);
  }
</script>

<!-- SettleSingleSheet (ui/screen/settle/SettleSingleSheet.kt): Lg avatar, "Pay X", Amount, the action. -->
<Sheet title={received ? 'Record a payment' : 'Settle up'} {onclose}>
  {#if picked}
    <div class="who">
      <Avatar name={other} size="lg" />
      <div class="who-t">{received ? `${other} paid you` : `Pay ${other}`}</div>
      <div class="who-s">
        {#if received}
          <b class="mono">{money(picked.amountSubunits, picked.currency)}</b> left{forExpense ? ` on ${forExpense.title}` : ''}
        {:else}
          You owe <b class="mono">{money(picked.amountSubunits, picked.currency)}</b>{forExpense ? ` for ${forExpense.title}` : ''}
        {/if}
      </div>
    </div>
  {/if}
  {#if debts.length > 1}
    <span class="flbl">Who did you pay?</span>
    <div class="chips" style="margin:0 0 16px">
      {#each debts as d (d.creditorUserId + d.currency)}
        <button type="button" class="chip chip--pick" class:chip--picked={picked === d} onclick={() => pick(d)}>
          <Avatar name={store.name(d.creditorUserId)} />{store.name(d.creditorUserId)}
        </button>
      {/each}
    </div>
  {/if}
  {#if picked}
    <label class="flbl" for="settle-amount">Amount</label>
    <div class="amtin">
      <span class="mono amtin-s">{symbol(picked.currency)}</span>
      <input
        id="settle-amount"
        class="mono"
        type="text"
        inputmode="decimal"
        placeholder="0.00"
        aria-label={received ? `What ${other} paid you` : 'What you paid'}
        bind:value={text}
      />
    </div>
    <p class="helper">
      {received ? `Up to the ${money(picked.amountSubunits, picked.currency)} left. Paid part of it? Enter just what you got.` : `Paying back up to your ${money(picked.amountSubunits, picked.currency)}. Send it however you two usually do.`}
    </p>
  {/if}
  {#if problem}<div class="note note--amber" role="alert">{problem}</div>{/if}
  <button type="button" class="btn" aria-busy={store.busy} onclick={save}>
    {picked ? (received ? `${other} paid me` : `I paid ${other}`) : 'Record payment'}
  </button>
  <p class="helper center" style="margin-top:10px">
    {#if received}
      {other} gets a notification if they use the app.
    {:else}
      {picked ? `${other} gets` : 'They get'} a notification and can say if it didn't arrive.
    {/if}
  </p>
</Sheet>

<style>
  .who {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    margin-bottom: 16px;
    text-align: center;
  }
  .who-t {
    font-size: 18px;
    font-weight: 600;
  }
  .who-s {
    font-size: 13px;
    color: var(--ink-2);
    margin-top: -8px;
  }
  .who-s b {
    color: var(--ink);
    font-weight: 600;
  }
  /* EvAmountInput: 60px, 12px corners, symbol prefix, mono semibold. */
  .amtin {
    display: flex;
    align-items: center;
    gap: 8px;
    min-height: 60px;
    padding: 0 14px;
    border: 1px solid var(--border-strong);
    border-radius: 12px;
  }
  .amtin:focus-within {
    border-color: var(--blue);
    box-shadow: inset 0 0 0 1px var(--blue);
  }
  .amtin-s {
    font-size: 24px;
    font-weight: 600;
    color: var(--ink-3);
  }
  .amtin input {
    flex: 1;
    min-width: 0;
    border: none;
    outline: none;
    font-size: 24px;
    font-weight: 600;
    color: var(--ink);
    background: none;
  }
  .helper {
    font-size: 12px;
    color: var(--ink-2);
    margin: 6px 0 16px;
    line-height: 1.45;
  }
</style>
