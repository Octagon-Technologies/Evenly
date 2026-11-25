<script lang="ts">
  /**
   * `AddExpenseScreen` + `DivideSplitBody` (ui/screen/expense/) for a guest: a pushed screen with the
   * scan card, Title, the large amount card, Paid by, the people chips, How to split, and a docked Save.
   * Add an expense, or edit one, from the web group page. Split evenly, by exact amounts, or (adding
   * only) by items that each person then claims on the bill's own page. Adding can start from a receipt
   * photo: evenly/exact reads the total, by-items reads the lines plus tax and tip. The scan only fills
   * the form; nothing is saved until the guest taps Add (docs/features/web-group-page.md).
   *
   * Save stays live and says what is missing on tap; nothing is greyed out without a reason (AGENTS.md §7).
   */
  import { untrack } from 'svelte';
  import Sheet from '../../components/Sheet.svelte';
  import Screen from '../../components/Screen.svelte';
  import Avatar from '../../components/Avatar.svelte';
  import Icon from '../../components/Icon.svelte';
  import { amount, money, parseAmount, symbol } from '../../lib/format.ts';
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
  let typingName = $state(false);
  let pickingPayer = $state(false);
  let problem = $state<string | null>(null);
  /** From an itemized scan: split in proportion to what each person had, like an app-made bill. */
  let extras = $state<{ tax: number; tip: number; gratuity: number; discount: number; other: number } | null>(null);
  let scanNote = $state<string | null>(null);
  let fileInput = $state<HTMLInputElement | null>(null);

  const extrasTotal = $derived(extras ? extras.tax + extras.tip + extras.gratuity + extras.other - extras.discount : 0);
  const extrasLabel = $derived.by(() => {
    if (!extras) return '';
    const parts: string[] = [];
    if (extras.tax) parts.push(`tax ${money(extras.tax, currency)}`);
    if (extras.tip + extras.gratuity) parts.push(`tip ${money(extras.tip + extras.gratuity, currency)}`);
    if (extras.other) parts.push(`fees ${money(extras.other, currency)}`);
    if (extras.discount) parts.push(`discount -${money(extras.discount, currency)}`);
    return parts.join(', ');
  });

  async function scanPicked(e: Event) {
    const input = e.currentTarget as HTMLInputElement;
    const files = [...(input.files ?? [])];
    input.value = '';
    if (files.length === 0) return;
    scanNote = null;
    problem = null;
    const res = await store.scan(files, mode === 'ITEMIZED' ? null : 'summary');
    if (res.problem) {
      problem = res.problem;
      return;
    }
    const read = res.receipt ?? res.summary;
    const readCurrency = res.receipt?.currency ?? res.summary?.currency ?? null;
    if (readCurrency && readCurrency !== currency) {
      problem = `That receipt is in ${readCurrency} and this group uses ${currency}. Type the ${currency} amount in.`;
      return;
    }
    if (!title.trim()) title = (res.summary?.short_title ?? read?.merchant_name ?? '').slice(0, 80);
    if (res.receipt) {
      const r = res.receipt;
      lines = r.items.map((i) => ({ label: i.label, qty: String(i.quantity), price: amount(i.line_total_subunits, currency) }));
      const x = { tax: r.tax_subunits, tip: r.tip_subunits, gratuity: r.gratuity_subunits, discount: r.discount_subunits, other: r.other_charges_subunits };
      extras = x.tax + x.tip + x.gratuity + x.discount + x.other > 0 ? x : null;
      scanNote = res.verified ? 'Filled in from the receipt. Give it a quick check.' : "Filled in from the receipt, but the lines don't add up to its total. Check them against it.";
    } else if (res.summary && res.summary.total_subunits > 0) {
      amountText = amount(res.summary.total_subunits, currency);
      scanNote = 'Filled in from the receipt. Give it a quick check.';
    } else {
      problem = "Couldn't read the total on that one. Type it in.";
    }
  }

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

  /** Even shares for the per-person rows: equal parts, the leftover cents to the first people. Display only. */
  const payerName = $derived(payer === me ? (store.view?.me.name ?? 'You') : store.name(payer));

  const evenShares = $derived.by(() => {
    const total = parseAmount(amountText, currency) ?? 0;
    const n = chosen.length;
    if (n === 0 || total <= 0) return new Map<string, number>();
    const base = Math.floor(total / n);
    const extra = total - base * n;
    return new Map(chosen.map((p, i) => [p.key, base + (i < extra ? 1 : 0)]));
  });

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
      const withExtras = mode === 'ITEMIZED' && extras;
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
        extras: withExtras ? extras ?? undefined : undefined,
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

<Screen title={editing ? 'Edit expense' : 'New expense'} nav={editing ? 'back' : 'close'} onback={onclose}>
  <div class="xf">
    {#if !editing}
      <input bind:this={fileInput} class="xf-file" type="file" accept="image/*,application/pdf" multiple onchange={scanPicked} />
      <!-- ScanReceiptHero: blue-ringed card, camera tile. iOS's file picker offers camera, photos and files itself. -->
      <button type="button" class="scan" disabled={store.busy} onclick={() => fileInput?.click()}>
        <span class="scan-ic"><Icon name="camera" size={22} /></span>
        <span class="grow">
          <span class="scan-t">{store.busy ? 'Reading the receipt…' : 'Scan the receipt'}</span>
          <span class="scan-s">
            {mode === 'ITEMIZED' ? "We'll read the line items. Several pages count as one bill." : "We'll read the total and fill it in."}
          </span>
        </span>
      </button>
      {#if scanNote}<div class="note note--blue">{scanNote}</div>{/if}
    {/if}

    <div>
      <label class="flbl" for="xf-title">Title</label>
      <input id="xf-title" class="inp" type="text" maxlength="80" placeholder="What was it for?" bind:value={title} />
    </div>

    {#if mode !== 'ITEMIZED'}
      <!-- DivideSplitBody's amount card: a large mono input under its currency symbol. -->
      <div class="evcard evcard--pad amtcard">
        <label class="amtrow" for="xf-amt">
          <span class="mono amt-sym">{symbol(currency)}</span>
          <input id="xf-amt" class="mono amt-in" type="text" inputmode="decimal" placeholder="0.00" bind:value={amountText} />
        </label>
        <span class="tag">{currency}</span>
      </div>
    {/if}

    <div>
      <span class="flbl">Paid by</span>
      <button type="button" class="pick-row" onclick={() => (pickingPayer = true)}>
        <Avatar name={payerName} me={payer === me} size="sm" />
        <span class="grow pick-name">{payer === me ? `You (${payerName})` : payerName}</span>
        <span class="pick-change">Change</span>
      </button>
    </div>

    <div>
      <span class="flbl">{mode === 'ITEMIZED' ? 'Who was there?' : "Who's splitting this?"}</span>
      <div class="chips" style="margin-top:0">
        {#each people as p (p.key)}
          <button
            type="button"
            class="chip chip--pick"
            class:chip--picked={p.on}
            aria-pressed={p.on}
            onclick={() => (p.on = !p.on)}
          >
            <Avatar name={p.userId === me ? (store.view?.me.name ?? 'You') : label(p)} me={p.userId === me} />{p.userId === me ? 'You' : label(p)}
          </button>
        {/each}
        {#if !typingName}
          <button type="button" class="chip chip--pick" onclick={() => (typingName = true)}>
            <span class="addic"><Icon name="plus" size={15} /></span>Add
          </button>
        {/if}
      </div>
      {#if typingName}
        <div class="xf-new">
          <!-- svelte-ignore a11y_autofocus (opened by the Add chip, so focus is expected) -->
          <input
            class="inp"
            type="text"
            maxlength="40"
            placeholder="Their name"
            aria-label="Their name"
            autofocus
            bind:value={newName}
            onkeydown={(e) => e.key === 'Enter' && (e.preventDefault(), addName(), (typingName = false))}
          />
          <button type="button" class="btn btn--sm" onclick={() => (addName(), (typingName = false))}>Add</button>
        </div>
      {/if}
    </div>

    <div>
      <span class="flbl">How to split</span>
      <div class="seg" role="tablist" aria-label="How to split">
        <button type="button" role="tab" aria-selected={mode === 'EVEN'} onclick={() => (mode = 'EVEN')}>Even</button>
        <button type="button" role="tab" aria-selected={mode === 'EXACT'} onclick={() => (mode = 'EXACT')}>Exact</button>
        {#if !editing}
          <button type="button" role="tab" aria-selected={mode === 'ITEMIZED'} onclick={() => (mode = 'ITEMIZED')}>By items</button>
        {/if}
      </div>
    </div>

    {#if mode === 'ITEMIZED'}
      <div>
        <div class="sec">Items</div>
        <div class="evcard">
          {#each lines as l, i (i)}
            <div class="xf-line hair">
              <input class="inp" type="text" placeholder="Item" aria-label="Item name" bind:value={l.label} />
              <input class="inp xf-qty" type="text" inputmode="numeric" aria-label="Quantity" bind:value={l.qty} />
              <input class="inp mono xf-price" type="text" inputmode="decimal" placeholder="0.00" aria-label="Line price" bind:value={l.price} />
            </div>
          {/each}
        </div>
        <button type="button" class="addline" onclick={() => lines.push({ label: '', qty: '1', price: '' })}>
          <Icon name="plus" size={15} />Add item
        </button>
        {#if extras}
          <p class="sub">
            Plus {extrasLabel}, split by what each person had.
            <button type="button" class="link" onclick={() => (extras = null)}>Leave these off</button>
          </p>
        {/if}
        <div class="total"><span>Receipt total</span><b class="mono">{money(itemsTotal + extrasTotal, currency)}</b></div>
        <p class="sub">Everyone picks what they had on the bill's page.</p>
      </div>
    {:else if chosen.length > 0}
      <div class="evcard">
        {#each chosen as p (p.key)}
          <div class="prow hair">
            <Avatar name={p.userId === me ? (store.view?.me.name ?? 'You') : label(p)} me={p.userId === me} size="sm" />
            <span class="grow pname">{p.userId === me ? 'You' : label(p)}</span>
            {#if mode === 'EXACT'}
              <span class="inl">
                <span class="mono inl-s">{symbol(currency)}</span>
                <input class="mono" type="text" inputmode="decimal" placeholder="0.00" aria-label={`${label(p)}'s amount`} bind:value={p.exact} />
              </span>
            {:else}
              <span class="mono pamt">{money(evenShares.get(p.key) ?? 0, currency)}</span>
            {/if}
          </div>
        {/each}
      </div>
    {:else}
      <p class="sub">Add at least one participant.</p>
    {/if}

    {#if problem}<div class="note note--amber" role="alert">{problem}</div>{/if}
  </div>

  {#snippet dock()}
    <button type="button" class="btn" aria-busy={store.busy} onclick={save}>{editing ? 'Save changes' : 'Add expense'}</button>
  {/snippet}
</Screen>

{#if pickingPayer}
  <Sheet title="Paid by" onclose={() => (pickingPayer = false)}>
    <div class="evcard">
      {#each members as m (m.userId)}
        <button
          type="button"
          class="prow hair payopt"
          aria-pressed={payer === m.userId}
          onclick={() => ((payer = m.userId), (pickingPayer = false))}
        >
          <Avatar name={m.name} me={m.userId === me} size="sm" />
          <span class="grow pname">{m.userId === me ? `You (${m.name})` : m.name}</span>
          {#if payer === m.userId}<span style="color:var(--blue)"><Icon name="check" size={20} /></span>{/if}
        </button>
      {/each}
    </div>
  </Sheet>
{/if}

<style>
  .xf {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  .xf-file {
    display: none;
  }
  .scan {
    display: flex;
    align-items: center;
    gap: 13px;
    width: 100%;
    padding: 15px 14px 14px;
    border: 1px solid var(--blue);
    border-radius: 16px;
    background: var(--page);
    font: inherit;
    color: inherit;
    text-align: left;
    cursor: pointer;
  }
  .scan-ic {
    flex: none;
    width: 46px;
    height: 46px;
    border-radius: 13px;
    background: var(--blue);
    color: #fff;
    display: grid;
    place-items: center;
  }
  .scan-t {
    display: block;
    font-size: 16px;
    font-weight: 700;
  }
  .scan-s {
    display: block;
    font-size: 12px;
    line-height: 16px;
    color: var(--ink-2);
    margin-top: 3px;
  }
  .amtcard {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
  }
  .amtrow {
    display: flex;
    align-items: baseline;
    justify-content: center;
    gap: 8px;
    width: 100%;
  }
  .amt-sym {
    font-size: 22px;
    font-weight: 600;
    color: var(--ink-3);
  }
  /* EvenlyTheme.amounts.input: 52px mono semibold. */
  .amt-in {
    width: 100%;
    max-width: 240px;
    border: none;
    outline: none;
    background: none;
    font-size: 52px;
    font-weight: 600;
    letter-spacing: -1.5px;
    color: var(--ink);
    text-align: center;
  }
  .amt-in::placeholder {
    color: var(--ink-3);
  }
  .pick-row {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    min-height: 52px;
    padding: 0 14px;
    border: 1px solid var(--border-strong);
    border-radius: 12px;
    background: var(--page);
    font: inherit;
    color: inherit;
    text-align: left;
    cursor: pointer;
  }
  .pick-name {
    font-size: 16px;
  }
  .pick-change {
    color: var(--blue);
    font-size: 15px;
    font-weight: 600;
  }
  .addic {
    color: var(--blue);
    display: flex;
    padding-left: 4px;
  }
  .xf-new {
    display: flex;
    gap: 8px;
    margin-top: 10px;
    align-items: center;
  }
  .xf-new .btn {
    flex: none;
    min-height: 44px;
  }
  .prow {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 16px;
    width: 100%;
    background: none;
    border-left: none;
    border-right: none;
    border-bottom: none;
    font: inherit;
    color: inherit;
    text-align: left;
  }
  .prow:first-child {
    border-top: none;
  }
  .payopt {
    cursor: pointer;
    min-height: 56px;
  }
  .pname {
    font-size: 15px;
    font-weight: 600;
  }
  .pamt {
    font-weight: 600;
  }
  .inl {
    display: flex;
    align-items: center;
    gap: 4px;
    width: 104px;
    height: 44px;
    padding: 0 10px;
    border: 1px solid var(--border-strong);
    border-radius: 10px;
  }
  .inl:focus-within {
    border-color: var(--blue);
  }
  .inl-s {
    color: var(--ink-3);
  }
  .inl input {
    width: 100%;
    min-width: 0;
    border: none;
    outline: none;
    font-size: 16px;
    text-align: right;
    background: none;
  }
  .xf-line {
    display: flex;
    gap: 6px;
    padding: 8px;
  }
  .xf-line:first-child {
    border-top: none;
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
  }
  .addline {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 10px 4px;
    background: none;
    border: none;
    color: var(--blue);
    font: 600 14px var(--font);
    cursor: pointer;
    min-height: 44px;
  }
  .total {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    padding: 4px 4px 0;
    font-size: 14px;
    color: var(--ink-2);
  }
  .total b {
    font-size: 20px;
    font-weight: 600;
    color: var(--ink);
  }
</style>
