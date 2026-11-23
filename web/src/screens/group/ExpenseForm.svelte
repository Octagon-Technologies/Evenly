<script lang="ts">
  /**
   * Add an expense, or edit one, from the web group page. Split evenly, by exact amounts, or (adding
   * only) by typed-in items that each person then claims on the bill's own page. No receipt scan here:
   * OCR is a per-call cost and this page is a public link (docs/features/web-group-page.md non-goals).
   *
   * Save stays live and says what is missing on tap; nothing is greyed out without a reason (AGENTS.md §7).
   */
  import { untrack } from 'svelte';
  import Sheet from '../../components/Sheet.svelte';
  import { amount, money, parseAmount } from '../../lib/format.ts';
  import type { GroupStore } from '../../lib/group.svelte.ts';
  import type { GroupExpense, PersonIn } from '../../lib/api.ts';

  interface Props {
    store: GroupStore;
    /** Set when editing; absent when adding. */
    expense?: GroupExpense;
    onclose: () => void;
    onsaved: (message: string, billToken: string | null) => void;
  }

  const { store, expense, onclose, onsaved }: Props = $props();

  type Mode = 'EVEN' | 'EXACT' | 'ITEMIZED';
  interface Person {
    key: string;
    userId?: string;
    newName?: string;
    on: boolean;
    exact: string;
  }
  interface Line {
    label: string;
    qty: string;
    price: string;
  }

  // Seeded once, from the expense the form opened on; a background reload must not reset what is typed.
  const editing = untrack(() => expense ?? null);
  const members = untrack(() => store.view?.members ?? []);
  const currency = untrack(() => editing?.currency ?? store.view?.header.baseCurrency ?? 'USD');
  const me = untrack(() => store.me);

  let title = $state(editing?.title ?? '');
  let amountText = $state(editing ? amount(editing.amountSubunits, currency) : '');
  let payer = $state(editing?.payerUserId ?? me);
  let mode = $state<Mode>((editing?.splitMode as Mode) ?? 'EVEN');
  // You first, then the group in join order. Anyone already on the expense but no longer in the group
  // stays listed and ticked: dropping them from the form would silently take them off the bill.
  const ids = untrack(() => {
    const all = [...members.map((m) => m.userId), ...(editing?.shares.map((s) => s.userId) ?? [])];
    return [...new Set([me, ...all])].filter((id) => all.includes(id));
  });
  let people = $state<Person[]>(
    ids.map((userId) => {
      const share = editing?.shares.find((s) => s.userId === userId);
      return {
        key: userId,
        userId,
        on: editing ? !!share : true,
        exact: share ? amount(share.owedSubunits, currency) : '',
      };
    }),
  );
  let lines = $state<Line[]>([{ label: '', qty: '1', price: '' }]);
  let newName = $state('');
  let problem = $state<string | null>(null);

  const chosen = $derived(people.filter((p) => p.on));
  const itemsTotal = $derived(
    lines.reduce((t, l) => t + (parseAmount(l.price, currency) ?? 0), 0),
  );
  const exactTotal = $derived(chosen.reduce((t, p) => t + (parseAmount(p.exact, currency) ?? 0), 0));

  function addName() {
    const name = newName.trim().replace(/\s+/g, ' ');
    if (!name) return;
    const existing = people.find(
      (p) => (p.userId ? store.names.get(p.userId) : p.newName)?.toLowerCase() === name.toLowerCase(),
    );
    if (existing) {
      existing.on = true;
      problem = `${name} is already here, so they're ticked.`;
    } else {
      people.push({ key: `new:${name}`, newName: name, on: true, exact: '' });
      problem = null;
    }
    newName = '';
  }

  function label(p: Person): string {
    return p.userId ? store.name(p.userId) : (p.newName ?? '');
  }

  function toPeople(): PersonIn[] {
    return chosen.map((p) => {
      const base: PersonIn = p.userId ? { userId: p.userId } : { newName: p.newName };
      if (mode === 'EXACT') base.exactSubunits = parseAmount(p.exact, currency) ?? 0;
      return base;
    });
  }

  /** Everything still missing, in the order the guest meets the fields. */
  function missing(total: number | null): string | null {
    if (!title.trim()) return 'Say what it was for.';
    if (mode === 'ITEMIZED') {
      if (lines.some((l) => !l.label.trim() || (parseAmount(l.price, currency) ?? 0) <= 0 || !(Number(l.qty) >= 1))) {
        return 'Every item needs a name, a quantity and a price.';
      }
    } else if (total === null || total <= 0) {
      return 'Enter how much it was.';
    }
    if (chosen.length === 0) return mode === 'ITEMIZED' ? 'Tick who was there.' : 'Tick who it is split between.';
    if (mode === 'EXACT' && total !== null && exactTotal !== total) {
      return `The amounts add up to ${money(exactTotal, currency)}, not ${money(total, currency)}.`;
    }
    return null;
  }

  async function save() {
    const total = mode === 'ITEMIZED' ? itemsTotal : parseAmount(amountText, currency);
    problem = missing(total);
    if (problem) return;

    if (!editing) {
      const res = await store.create({
        title: title.trim(),
        amountSubunits: total ?? 0,
        currency,
        date: new Date().toISOString().slice(0, 10),
        payerUserId: payer,
        splitMode: mode,
        people: toPeople(),
        items:
          mode === 'ITEMIZED'
            ? lines.map((l) => ({ label: l.label.trim(), quantity: Number(l.qty), lineTotalSubunits: parseAmount(l.price, currency) ?? 0 }))
            : undefined,
      });
      if (res.problem) problem = res.problem;
      else onsaved(mode === 'ITEMIZED' ? 'Added. Now pick what you had.' : 'Added. Everyone will see it.', res.billToken);
      return;
    }

    const before = editing.shares.map((s) => `${s.userId}:${s.owedSubunits}`).sort().join();
    const moneyChanged =
      total !== editing.amountSubunits ||
      payer !== editing.payerUserId ||
      mode !== editing.splitMode ||
      chosen.some((p) => !p.userId) ||
      chosen.map((p) => p.userId).sort().join() !== editing.shares.map((s) => s.userId).sort().join() ||
      (mode === 'EXACT' && chosen.map((p) => `${p.userId}:${parseAmount(p.exact, currency)}`).sort().join() !== before);
    const titleChanged = title.trim() !== editing.title;
    if (!moneyChanged && !titleChanged) {
      onclose();
      return;
    }
    const res = await store.edit({
      expenseId: editing.id,
      baseSplitVersion: editing.splitVersion,
      title: titleChanged ? title.trim() : undefined,
      ...(moneyChanged
        ? { amountSubunits: total ?? 0, payerUserId: payer ?? undefined, splitMode: mode as 'EVEN' | 'EXACT', people: toPeople() }
        : {}),
    });
    if (res) problem = res;
    else onsaved('Saved. Anyone can undo it from the expense.', null);
  }
</script>

<Sheet title={editing ? 'Edit expense' : 'Add expense'} {onclose}>
  <div class="h2" style="margin-bottom:14px">{editing ? 'Edit expense' : 'Add an expense'}</div>

  <div class="field">
    <label class="flbl" for="xf-title">What for</label>
    <input id="xf-title" class="inp" type="text" maxlength="80" placeholder="Dinner, Uber, groceries…" bind:value={title} />
  </div>

  <div class="field">
    <div class="flbl">How to split</div>
    <div class="xf-seg" role="tablist" aria-label="How to split">
      <button type="button" role="tab" aria-selected={mode === 'EVEN'} class:on={mode === 'EVEN'} onclick={() => (mode = 'EVEN')}>Evenly</button>
      <button type="button" role="tab" aria-selected={mode === 'EXACT'} class:on={mode === 'EXACT'} onclick={() => (mode = 'EXACT')}>Exact amounts</button>
      {#if !editing}
        <button type="button" role="tab" aria-selected={mode === 'ITEMIZED'} class:on={mode === 'ITEMIZED'} onclick={() => (mode = 'ITEMIZED')}>By items</button>
      {/if}
    </div>
  </div>

  {#if mode === 'ITEMIZED'}
    <div class="field">
      <div class="flbl">Items</div>
      {#each lines as l, i (i)}
        <div class="xf-line">
          <input class="inp" type="text" placeholder="Item" aria-label="Item name" bind:value={l.label} />
          <input class="inp xf-qty" type="text" inputmode="numeric" aria-label="Quantity" bind:value={l.qty} />
          <input class="inp mono xf-price" type="text" inputmode="decimal" placeholder="0.00" aria-label="Line price" bind:value={l.price} />
        </div>
      {/each}
      <button type="button" class="link" onclick={() => lines.push({ label: '', qty: '1', price: '' })}>+ Add item</button>
      <div class="sub">Total {money(itemsTotal, currency)}. Everyone picks what they had on the bill's page.</div>
    </div>
  {:else}
    <div class="field">
      <label class="flbl" for="xf-amt">How much</label>
      <input id="xf-amt" class="inp mono" type="text" inputmode="decimal" placeholder="0.00" bind:value={amountText} />
    </div>
  {/if}

  <div class="field">
    <label class="flbl" for="xf-payer">Who paid</label>
    <select id="xf-payer" class="inp" bind:value={payer}>
      {#each members as m (m.userId)}
        <option value={m.userId}>{m.userId === store.me ? `You (${m.name})` : m.name}</option>
      {/each}
    </select>
  </div>

  <div class="field">
    <div class="flbl">{mode === 'ITEMIZED' ? 'Who was there' : 'Split between'}</div>
    {#each people as p (p.key)}
      <div class="xf-person">
        <label class="grow xf-check">
          <input type="checkbox" bind:checked={p.on} />
          <span>{label(p)}{p.newName ? ' (new)' : ''}</span>
        </label>
        {#if mode === 'EXACT' && p.on}
          <input class="inp mono xf-price" type="text" inputmode="decimal" placeholder="0.00" aria-label={`${label(p)}'s amount`} bind:value={p.exact} />
        {/if}
      </div>
    {/each}
    <div class="xf-new">
      <input class="inp" type="text" maxlength="40" placeholder="Someone else? Type their name" bind:value={newName}
        onkeydown={(e) => e.key === 'Enter' && (e.preventDefault(), addName())} />
      <button type="button" class="btn btn--ghost btn--sm xf-add" onclick={addName}>Add</button>
    </div>
  </div>

  {#if problem}<div class="note note--amber" role="alert">{problem}</div>{/if}
  <button type="button" class="btn" disabled={store.busy} onclick={save}>{editing ? 'Save changes' : 'Add expense'}</button>
</Sheet>

<style>
  .xf-seg {
    display: flex;
    background: var(--surface);
    border-radius: var(--r-pill);
    padding: 3px;
  }
  .xf-seg button {
    flex: 1;
    border: 0;
    background: none;
    font: 600 13px var(--font);
    color: var(--ink-2);
    padding: 9px 0;
    border-radius: var(--r-pill);
    cursor: pointer;
  }
  .xf-seg button.on {
    background: var(--page);
    color: var(--ink);
    box-shadow: 0 1px 3px rgba(11, 18, 32, 0.08);
  }
  .xf-line {
    display: flex;
    gap: 6px;
    margin-bottom: 6px;
  }
  .xf-qty {
    width: 52px;
    flex-shrink: 0;
    padding: 0 8px;
    text-align: center;
  }
  .xf-price {
    width: 96px;
    flex-shrink: 0;
    min-height: 44px;
  }
  .xf-person {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 0;
    border-bottom: 1px solid var(--border);
  }
  .xf-check {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 15px;
    min-height: 40px;
    cursor: pointer;
  }
  .xf-check input {
    width: 20px;
    height: 20px;
    accent-color: var(--blue);
  }
  .xf-new {
    display: flex;
    gap: 8px;
    margin-top: 10px;
  }
  .xf-add {
    width: auto;
    padding: 0 16px;
    flex-shrink: 0;
  }
</style>
