<script lang="ts">
  /**
   * One expense, opened from the group page: who paid, who owes what, its change log with Undo, and
   * Edit. An itemized bill is changed line by line on its own claim page, so it links there instead.
   *
   * Edits apply at once and anyone in the group may undo them, the same rule as a bill line
   * (web/AGENTS.md): there is no approval queue to wait on.
   */
  import Sheet from '../../components/Sheet.svelte';
  import { money, shortDate } from '../../lib/format.ts';
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
  <Sheet title={expense.title} {onclose}>
    <div class="h2">{expense.title}</div>
    <div class="sub">
      {money(expense.amountSubunits, expense.currency)} · {store.name(expense.payerUserId)} paid · {shortDate(expense.date)}
    </div>

    {#if expense.splitMode === 'ITEMIZED'}
      <div class="note note--blue" style="margin-top:14px">
        Split by items: each person pays for what they had.
        {#if token}
          <a class="link" href={`/b/${token}`}>Open the bill</a> to see the items{expense.iClaimed ? ' or change yours' : ' and pick yours'}.
        {:else}
          You weren't listed on it, so there's nothing for you to claim.
        {/if}
      </div>
    {/if}

    {#if expense.shares.length > 0}
      <div class="card" style="margin-top:14px">
        {#each expense.shares as s (s.userId)}
          <div class="row" style="align-items:center">
            <span class="grow lbl">{store.name(s.userId)}</span>
            <span class="mono amt">{money(s.owedSubunits, expense.currency)}</span>
            {#if s.userId !== expense.payerUserId}
              <span class="meta" style="margin:0;min-width:64px;text-align:right">
                {s.remainingSubunits <= 0 ? 'paid back' : `${money(s.remainingSubunits, expense.currency)} left`}
              </span>
              {#if canReceive && s.remainingSubunits > 0}
                <button type="button" class="link" style="margin-left:10px" onclick={() => receiveFrom(s.userId, s.remainingSubunits)}>
                  Got paid
                </button>
              {/if}
            {:else}
              <span class="meta" style="margin:0;min-width:64px;text-align:right">paid</span>
            {/if}
          </div>
        {/each}
      </div>
    {/if}

    {#if edits.length > 0}
      <div class="flbl" style="margin-top:6px">Changes</div>
      {#each edits as x (x.id)}
        <div class="sx" class:sx--undone={x.undone}>
          <div class="grow">
            <div>{store.name(x.actorUserId)} {describe(x.before, x.after)}</div>
            <div class="meta">{time(x.createdAt)}{x.undone ? ' · undone' : ''}</div>
          </div>
          {#if !x.undone && !store.view?.readOnly}
            <button type="button" class="link" disabled={store.busy} onclick={() => undo(x.id)}>Undo</button>
          {/if}
        </div>
      {/each}
    {/if}

    {#if problem}<div class="note note--amber" role="alert">{problem}</div>{/if}
    <div class="spacer"></div>
    {#if owe && !store.view?.readOnly}
      <button type="button" class="btn" style="margin-bottom:10px" onclick={() => (paying = true)}>
        Pay {store.name(owe.creditorUserId)} back {money(owe.amountSubunits, owe.currency)}
      </button>
    {/if}
    {#if editable}
      <button type="button" class="btn btn--ghost" onclick={() => (editing = true)}>Edit expense</button>
    {/if}
  </Sheet>
{/if}

<style>
  .sx {
    display: flex;
    gap: 10px;
    align-items: center;
    font-size: 13.5px;
    padding: 10px 0;
    border-top: 1px solid var(--border);
  }
  .sx--undone {
    color: var(--ink-3);
  }
</style>
