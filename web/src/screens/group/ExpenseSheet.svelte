<script lang="ts">
  /**
   * `ExpenseDetailScreen` (ui/screen/expense/ExpenseDetailScreen.kt) for a guest: a pushed screen with
   * the hero card (amount, "total expense", your status, who paid, Edit), the split list and the change
   * log. One expense, opened from the group page: who paid, who owes what, its change log with Undo, and
   * Edit. An itemized bill is changed line by line on its own claim page, so it links there instead.
   *
   * Edits apply at once and anyone in the group may undo them, the same rule as a bill line
   * (web/AGENTS.md): there is no approval queue to wait on.
   */
  import Screen from '../../components/Screen.svelte';
  import Avatar from '../../components/Avatar.svelte';
  import Icon from '../../components/Icon.svelte';
  import { money, shortDate } from '../../lib/format.ts';
  import { category } from '../../lib/category.ts';
  import type { GroupStore } from '../../lib/group.svelte.ts';
  import type { ExpenseState, GroupExpense } from '../../lib/api.ts';
  import type { Debt } from '../../lib/money/balances.ts';
  import ExpenseForm from './ExpenseForm.svelte';
  import SettleSheet from './SettleSheet.svelte';

  interface Props {
    store: GroupStore;
    expense: GroupExpense;
    onclose: () => void;
    onsaved: (message: string) => void;
  }

  const { store, expense, onclose, onsaved }: Props = $props();

  let editing = $state(false);
  let paying = $state(false);
  /** Set when the guest paid this bill and is recording what one person paid them back. */
  let receiving = $state<Debt | null>(null);
  // What the guest still owes on this one bill, as a debt to its payer: the per-expense pay-back path.
  const owe = $derived.by(() => {
    const stake = store.stake(expense);
    if (!stake || stake.owedToMe || !expense.payerUserId) return null;
    return { debtorUserId: store.me, creditorUserId: expense.payerUserId, currency: expense.currency, amountSubunits: stake.subunits };
  });
  let problem = $state<string | null>(null);

  // Only the payer records money coming back in, and only from someone who still owes on this bill.
  const canReceive = $derived(!store.view?.readOnly && expense.payerUserId === store.me);
  function receiveFrom(userId: string, remainingSubunits: number) {
    receiving = { debtorUserId: userId, creditorUserId: store.me, currency: expense.currency, amountSubunits: remainingSubunits };
  }

  const editable = $derived(!store.view?.readOnly && (expense.splitMode === 'EVEN' || expense.splitMode === 'EXACT'));
  const edits = $derived(store.editsFor(expense.id));
  const token = $derived(store.billToken(expense));

  function time(ms: number): string {
    return new Date(ms).toLocaleString([], { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
  }

  /** "changed the total from $40.00 to $48.00 and took Bob off". Plain words, not field names. */
  function describe(before: ExpenseState, after: ExpenseState): string {
    const parts: string[] = [];
    if (before.title !== after.title) parts.push(`renamed it "${after.title}"`);
    if (before.amount !== after.amount) {
      parts.push(`changed the total from ${money(before.amount, expense.currency)} to ${money(after.amount, expense.currency)}`);
    }
    if (before.payer !== after.payer) parts.push(`set ${store.name(after.payer)} as the payer`);
    const was = new Set(before.shares.map((s) => s.user_id));
    const now = new Set(after.shares.map((s) => s.user_id));
    const added = [...now].filter((u) => !was.has(u)).map((u) => store.name(u));
    const removed = [...was].filter((u) => !now.has(u)).map((u) => store.name(u));
    if (added.length) parts.push(`added ${added.join(', ')}`);
    if (removed.length) parts.push(`took ${removed.join(', ')} off`);
    if (parts.length === 0) parts.push('changed the split');
    return parts.join(' and ');
  }

  async function undo(editId: string) {
    problem = await store.undo(editId);
    if (!problem) onsaved('Undone.');
  }
</script>

{#if paying && owe}
  <SettleSheet
    {store}
    forExpense={{ id: expense.id, title: expense.title, debt: owe }}
    onclose={() => (paying = false)}
    ondone={(m) => {
      paying = false;
      onsaved(m);
    }}
  />
{:else if receiving}
  <SettleSheet
    {store}
    forExpense={{ id: expense.id, title: expense.title, debt: receiving }}
    onclose={() => (receiving = null)}
    ondone={(m) => {
      receiving = null;
      onsaved(m);
    }}
  />
{:else if editing}
  <ExpenseForm
    {store}
    {expense}
    onclose={() => (editing = false)}
    onsaved={(m) => {
      editing = false;
      onsaved(m);
    }}
  />
{:else}
  {@const stake = store.stake(expense)}
  {@const iPaid = expense.payerUserId === store.me}
  {@const payerName = iPaid ? (store.view?.me.name ?? 'You') : store.name(expense.payerUserId)}
  {@const cat = category(expense.categoryId)}
  <Screen title={expense.title} onback={onclose}>
    <div class="evcard evcard--pad">
      <div class="hero-top">
        <div>
          <div class="mono hero-amt">{money(expense.amountSubunits, expense.currency)}</div>
          <div class="hero-k">total expense</div>
        </div>
        {#if expense.categoryId}
          <span class="tag tag--blue tag--lg" style="color:{cat.color}"><Icon name={cat.icon} size={16} /></span>
        {/if}
      </div>
      <!-- PersonalStatusBand: where the guest stands on this one bill. -->
      {#if stake}
        <div class="band" class:band--owed={stake.owedToMe}>
          {stake.owedToMe ? "You're owed" : 'You owe'}
          <b class="mono">{money(stake.subunits, expense.currency)}</b>
          {stake.owedToMe ? 'on this bill' : `to ${store.name(expense.payerUserId)}`}
        </div>
      {:else if store.settled(expense)}
        <div class="band band--owed"><Icon name="check" size={16} />All paid up</div>
      {/if}
      <div class="divider"></div>
      <div class="payer">
        <Avatar name={payerName} me={iPaid} size="sm" />
        <span class="grow"><b>{iPaid ? 'You' : payerName}</b>{iPaid ? ' paid this' : ' paid'}</span>
        <span class="payer-d">{shortDate(expense.date)}</span>
      </div>
      {#if editable}
        <button type="button" class="btn btn--ghost" style="margin-top:14px" onclick={() => (editing = true)}>
          <Icon name="edit" size={18} />Edit expense
        </button>
      {/if}
    </div>

    {#if expense.splitMode === 'ITEMIZED'}
      {#if token}
        <a class="btn btn--tonal" style="margin-top:12px;text-decoration:none" href={`/b/${token}`}>
          <Icon name="receipt" size={18} />{expense.iClaimed ? 'Change your items' : 'Pick your items'}
        </a>
      {:else}
        <p class="sub" style="margin-top:12px">Split by items. You weren't listed on it, so there's nothing for you to pick.</p>
      {/if}
    {/if}

    {#if expense.shares.length > 0}
      <div class="sec" style="margin-top:20px">{expense.splitMode === 'ITEMIZED' ? 'Split by items' : 'Split'}</div>
      <div class="evcard">
        {#each expense.shares as s (s.userId)}
          {@const me = s.userId === store.me}
          {@const isPayer = s.userId === expense.payerUserId}
          {@const name = me ? (store.view?.me.name ?? 'You') : store.name(s.userId)}
          {@const paidPart = s.owedSubunits > 0 ? Math.min(1, Math.max(0, (s.owedSubunits - s.remainingSubunits) / s.owedSubunits)) : 1}
          <div class="srow hair" class:srow--me={me}>
            <Avatar {name} {me} size="sm" />
            <div class="grow">
              <div class="sname"><b>{me ? 'You' : name}</b>{#if me}<span class="mine">&nbsp;· your share</span>{/if}</div>
              {#if isPayer}
                <div class="sk">Paid the bill</div>
              {:else if s.remainingSubunits <= 0}
                <div class="sk" style="color:var(--green)">Settled</div>
              {:else}
                <div class="prog-s"><i style="width:{paidPart * 100}%"></i></div>
                <div class="sk">{money(s.remainingSubunits, expense.currency)} left</div>
              {/if}
            </div>
            <div class="sv">
              <span class="mono" class:mine={me}>{money(s.owedSubunits, expense.currency)}</span>
              {#if !isPayer && canReceive && s.remainingSubunits > 0}
                <button type="button" class="link sv-a" onclick={() => receiveFrom(s.userId, s.remainingSubunits)}>Got paid</button>
              {:else}
                <span class="sk">{isPayer ? (me ? 'your share' : 'their share') : 'share'}</span>
              {/if}
            </div>
          </div>
        {/each}
      </div>
    {/if}

    {#if owe && !store.view?.readOnly}
      <button type="button" class="btn" style="margin-top:16px" onclick={() => (paying = true)}>
        Pay {store.name(owe.creditorUserId)} back {money(owe.amountSubunits, owe.currency)}
      </button>
    {/if}

    {#if edits.length > 0}
      <div class="sec" style="margin-top:20px">Activity</div>
      <div class="evcard">
        {#each edits as x (x.id)}
          <div class="sx hair" class:sx--undone={x.undone}>
            <div class="grow">
              <div><b>{store.name(x.actorUserId)}</b> {describe(x.before, x.after)}</div>
              <div class="sk">{time(x.createdAt)}{x.undone ? ' · undone' : ''}</div>
            </div>
            {#if !x.undone && !store.view?.readOnly}
              <button type="button" class="link" disabled={store.busy} onclick={() => undo(x.id)}>Undo</button>
            {/if}
          </div>
        {/each}
      </div>
    {/if}

    {#if problem}<div class="note note--amber" role="alert" style="margin-top:12px">{problem}</div>{/if}
  </Screen>
{/if}

<style>
  .hero-top {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
  }
  /* EvenlyTheme.amounts.hero: 40px mono semibold. */
  .hero-amt {
    font-size: 40px;
    font-weight: 600;
    letter-spacing: -1px;
    line-height: 1.1;
  }
  .hero-k {
    font-size: 12px;
    color: var(--ink-2);
    margin-top: 2px;
  }
  .tag--lg {
    height: 34px;
    padding: 0 10px;
  }
  .band {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-top: 14px;
    padding: 10px 12px;
    border-radius: 12px;
    background: var(--blue-tint);
    color: var(--blue);
    font-size: 14px;
  }
  .band b {
    font-weight: 600;
  }
  .band--owed {
    background: var(--green-tint);
    color: var(--green);
  }
  .divider {
    height: 1px;
    background: var(--border);
    margin: 14px 0;
  }
  .payer {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
  }
  .payer-d {
    font-size: 12px;
    color: var(--ink-2);
  }
  .srow {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px 16px;
  }
  .srow--me {
    background: var(--selection-tint);
  }
  .sname {
    font-size: 15px;
  }
  .sname b {
    font-weight: 600;
  }
  .mine {
    color: var(--blue);
    font-weight: 600;
  }
  .sk {
    font-size: 12px;
    color: var(--ink-3);
    margin-top: 2px;
  }
  .prog-s {
    height: 4px;
    border-radius: 2px;
    background: var(--blue-tint-2);
    margin-top: 6px;
    overflow: hidden;
  }
  .prog-s i {
    display: block;
    height: 100%;
    background: var(--blue);
  }
  .sv {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    font-size: 15px;
    font-weight: 600;
  }
  .sv-a {
    font-size: 13px;
    margin-top: 2px;
    min-height: 24px;
  }
  .sx {
    display: flex;
    gap: 10px;
    align-items: center;
    font-size: 14px;
    padding: 12px 16px;
  }
  .sx b {
    font-weight: 600;
  }
  .sx--undone {
    color: var(--ink-3);
  }
</style>
