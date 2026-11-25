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
  import type { ExpenseState, GroupExpense, GroupPayment } from '../../lib/api.ts';
  import type { Debt } from '../../lib/money/balances.ts';
  import ExpenseForm from './ExpenseForm.svelte';
  import SettleSheet from './SettleSheet.svelte';
  import ImageViewer from '../../components/ImageViewer.svelte';
  import { toProofFile, ProofImageError } from '../../lib/proofImage.ts';

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

  // Receipts, payment screenshots, the payments on this bill and its comments: the same things the app's
  // bill screen shows, so a guest can check a payment without asking (docs/features/payment-proof.md).
  const paymentsHere = $derived(store.paymentsOn(expense.id));
  const provedPayers = $derived(new Set(paymentsHere.filter((x) => x.payment.proof?.url).map((x) => x.payment.fromUserId)));
  const receipts = $derived((expense.receipts ?? []).filter((r) => r.url && !r.mimeType.includes('pdf')));
  const pdfReceipts = $derived((expense.receipts ?? []).filter((r) => r.url && r.mimeType.includes('pdf')));
  const proofs = $derived(paymentsHere.filter((x) => x.payment.proof?.url));
  let viewing = $state<{ url: string; title: string; caption?: string; detail?: string; payment?: GroupPayment } | null>(null);
  let draft = $state('');
  let fileInput = $state<HTMLInputElement | null>(null);
  let proofFor = $state<GroupPayment | null>(null);

  const APPS: Record<string, string> = { VENMO: 'Venmo', CASH_APP: 'Cash App', PAYPAL: 'PayPal', ZELLE: 'Zelle', MPESA: 'M-Pesa', CASH: 'Cash', BANK: 'Bank transfer', OTHER: 'Other' };
  function appLabel(app: string | null): string | null {
    return app ? (APPS[app] ?? app) : null;
  }

  function paymentTitle(p: GroupPayment): string {
    if (p.fromUserId === store.me) return `You paid ${store.name(p.toUserId)}`;
    if (p.toUserId === store.me) return `${store.name(p.fromUserId)} paid you`;
    return `${store.name(p.fromUserId)} paid ${store.name(p.toUserId)}`;
  }

  function day(ms: number): string {
    return new Date(ms).toLocaleDateString([], { day: 'numeric', month: 'short' });
  }

  function openProof(p: GroupPayment) {
    if (!p.proof?.url) return;
    viewing = {
      url: p.proof.url,
      title: 'Payment screenshot',
      caption: [`${paymentTitle(p)} ${money(p.amountSubunits, p.currency)}`, appLabel(p.paymentApp)].filter(Boolean).join(' · '),
      detail: `${day(p.settledAt)} · added by ${p.proof.uploadedBy === store.me ? 'you' : store.name(p.proof.uploadedBy)}`,
      payment: p,
    };
  }

  function addProof(p: GroupPayment) {
    proofFor = p;
    fileInput?.click();
  }

  async function onProofPicked(file: File | undefined) {
    const p = proofFor;
    proofFor = null;
    if (!file || !p) return;
    try {
      problem = await store.setProof(p.id, await toProofFile(file));
      if (!problem) onsaved('Screenshot added.');
    } catch (e) {
      problem = e instanceof ProofImageError ? e.message : "That image couldn't be read. Try another one.";
    }
  }

  async function removeProof(p: GroupPayment) {
    viewing = null;
    problem = await store.setProof(p.id, null);
    if (!problem) onsaved('Screenshot removed.');
  }

  async function send() {
    const body = draft.trim();
    if (!body) return;
    problem = await store.comment(expense.id, body);
    if (!problem) draft = '';
  }

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
              <div class="sname">
                <b>{me ? 'You' : name}</b>{#if me}<span class="mine">&nbsp;· your share</span>{/if}
                {#if provedPayers.has(s.userId)}<span class="clip" title="Has a payment screenshot" aria-label="Has a payment screenshot">📎</span>{/if}
              </div>
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

    {#if receipts.length > 0 || pdfReceipts.length > 0 || proofs.length > 0}
      <!-- The app's "Receipt" card: thumbnails in a row, payment screenshots after a rule. -->
      <div class="evcard evcard--pad" style="margin-top:16px">
        <div class="cardt">
          {receipts.length + pdfReceipts.length > 0 && proofs.length > 0 ? 'Receipt & payments' : proofs.length > 0 ? 'Payment screenshots' : 'Receipt'}
        </div>
        <div class="strip">
          {#each receipts as r (r.id)}
            <button type="button" class="shot" onclick={() => (viewing = { url: r.url!, title: 'Receipt' })}>
              <img src={r.url} alt="Receipt" />
              <span>Receipt</span>
            </button>
          {/each}
          {#each pdfReceipts as r (r.id)}
            <a class="shot shot--file" href={r.url} target="_blank" rel="noopener noreferrer"><b>PDF</b><span>Receipt</span></a>
          {/each}
          {#if receipts.length + pdfReceipts.length > 0 && proofs.length > 0}<span class="strip__rule" aria-hidden="true"></span>{/if}
          {#each proofs as x (x.payment.id)}
            <button type="button" class="shot" onclick={() => openProof(x.payment)}>
              <img src={x.payment.proof!.url} alt="Payment screenshot" />
              <span>{store.name(x.payment.fromUserId)} · {money(x.here, expense.currency)}</span>
            </button>
          {/each}
        </div>
      </div>
    {/if}

    {#if paymentsHere.length > 0}
      <div class="sec" style="margin-top:20px">Payments</div>
      <div class="evcard">
        {#each paymentsHere as x (x.payment.id)}
          {@const p = x.payment}
          <div class="prow hair">
            {#if p.proof?.url}
              <button type="button" class="pthumb" aria-label="See the payment screenshot" onclick={() => openProof(p)}>
                <img src={p.proof.url} alt="" />
              </button>
            {:else if store.inPayment(p)}
              <button type="button" class="pthumb pthumb--add" aria-label="Add a screenshot" disabled={store.busy} onclick={() => addProof(p)}>
                <Icon name="plus" size={16} />
              </button>
            {/if}
            <span class="grow">
              <span class="pt">{paymentTitle(p)}</span>
              <span class="sk" style="display:block">
                {[appLabel(p.paymentApp), day(p.settledAt), !p.proof?.url && store.inPayment(p) ? 'No screenshot' : null].filter(Boolean).join(' · ')}
              </span>
            </span>
            <span class="mono pa">{money(x.here, expense.currency)}</span>
          </div>
        {/each}
      </div>
    {/if}

    <div class="sec" style="margin-top:20px">Comments</div>
    <div class="evcard evcard--pad">
      {#each expense.comments ?? [] as c (c.id)}
        <div class="cmt" class:cmt--me={c.userId === store.me}>
          <b>{store.name(c.userId)}</b>
          <span class="cmt__body">{c.body}</span>
          <span class="sk">{time(c.createdAt)}</span>
        </div>
      {:else}
        <p class="sk" style="margin:0 0 12px">No comments yet.</p>
      {/each}
      {#if !store.view?.readOnly}
        <form class="cmt__form" onsubmit={(e) => { e.preventDefault(); void send(); }}>
          <input class="inp" type="text" placeholder="Add a comment" aria-label="Add a comment" maxlength="2000" bind:value={draft} />
          <button type="submit" class="btn btn--sm btn--tonal" style="width:auto" disabled={store.busy || !draft.trim()}>Send</button>
        </form>
      {/if}
    </div>

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
    <input
      bind:this={fileInput}
      type="file"
      accept="image/*"
      hidden
      onchange={(e) => {
        void onProofPicked((e.currentTarget as HTMLInputElement).files?.[0]);
        (e.currentTarget as HTMLInputElement).value = '';
      }}
    />
  </Screen>
  {#if viewing}
    {@const v = viewing}
    <ImageViewer
      url={v.url}
      title={v.title}
      caption={v.caption ?? null}
      detail={v.detail ?? null}
      onclose={() => (viewing = null)}
      onremove={v.payment && v.payment.proof?.uploadedBy === store.me && !store.view?.readOnly ? () => removeProof(v.payment!) : null}
    />
  {/if}
{/if}

<style>
  .clip {
    font-size: 12px;
    margin-left: 4px;
  }
  .cardt {
    font-size: 15px;
    font-weight: 600;
  }
  .strip {
    display: flex;
    gap: 12px;
    overflow-x: auto;
    padding-top: 10px;
    align-items: flex-start;
  }
  .strip__rule {
    width: 1px;
    align-self: stretch;
    background: var(--border);
    flex: none;
  }
  .shot {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 5px;
    background: none;
    border: 0;
    padding: 0;
    font: inherit;
    font-size: 12px;
    color: var(--ink-2);
    cursor: pointer;
    flex: none;
    text-decoration: none;
  }
  .shot img,
  .shot--file b {
    width: 72px;
    height: 92px;
    object-fit: cover;
    border-radius: 10px;
    border: 1px solid var(--border);
    background: var(--surface);
  }
  .shot--file b {
    display: grid;
    place-items: center;
    color: var(--ink-3);
  }
  .prow {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 16px;
  }
  .pt {
    display: block;
    font-size: 15px;
    font-weight: 600;
  }
  .pa {
    font-size: 15px;
    font-weight: 600;
  }
  .pthumb {
    width: 32px;
    height: 42px;
    border-radius: 7px;
    border: 1px solid var(--border);
    padding: 0;
    overflow: hidden;
    background: var(--surface);
    cursor: pointer;
    flex: none;
    display: grid;
    place-items: center;
  }
  .pthumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .pthumb--add {
    border: 1.3px dashed var(--blue);
    color: var(--blue);
    background: none;
  }
  .cmt {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 10px 12px;
    margin-bottom: 8px;
    border-radius: 12px;
    background: var(--surface);
    font-size: 14px;
  }
  .cmt--me {
    background: var(--blue-tint);
  }
  .cmt b {
    font-size: 13px;
    font-weight: 600;
  }
  .cmt__body {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }
  .cmt__form {
    display: flex;
    gap: 10px;
    align-items: center;
  }
  .cmt__form .inp {
    flex: 1;
  }
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
