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
  import type { ExpenseState, GroupExpense, GroupPayment } from '../../lib/api.ts';
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
            <span class="grow lbl">
              {store.name(s.userId)}
              {#if provedPayers.has(s.userId)}<span class="clip" title="Has a payment screenshot" aria-label="Has a payment screenshot">📎</span>{/if}
            </span>
            <span class="mono amt">{money(s.owedSubunits, expense.currency)}</span>
            {#if s.userId !== expense.payerUserId}
              <span class="meta" style="margin:0;min-width:64px;text-align:right">
                {s.remainingSubunits <= 0 ? 'paid back' : `${money(s.remainingSubunits, expense.currency)} left`}
              </span>
            {:else}
              <span class="meta" style="margin:0;min-width:64px;text-align:right">paid</span>
            {/if}
          </div>
        {/each}
      </div>
    {/if}

    {#if receipts.length > 0 || pdfReceipts.length > 0 || proofs.length > 0}
      <div class="flbl" style="margin-top:6px">
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
    {/if}

    {#if paymentsHere.length > 0}
      <div class="flbl" style="margin-top:6px">Payments</div>
      <div class="card">
        {#each paymentsHere as x (x.payment.id)}
          {@const p = x.payment}
          <div class="row" style="align-items:center;gap:10px">
            {#if p.proof?.url}
              <button type="button" class="pthumb" aria-label="See the payment screenshot" onclick={() => openProof(p)}>
                <img src={p.proof.url} alt="" />
              </button>
            {:else if store.inPayment(p)}
              <button type="button" class="pthumb pthumb--add" aria-label="Add a screenshot" disabled={store.busy} onclick={() => addProof(p)}>＋</button>
            {/if}
            <span class="grow">
              <span class="lbl">{paymentTitle(p)}</span>
              <span class="meta" style="display:block;margin:0">
                {[appLabel(p.paymentApp), day(p.settledAt), !p.proof?.url && store.inPayment(p) ? 'No screenshot' : null].filter(Boolean).join(' · ')}
              </span>
            </span>
            <span class="mono amt">{money(x.here, expense.currency)}</span>
          </div>
        {/each}
      </div>
    {/if}

    <div class="flbl" style="margin-top:6px">Comments</div>
    <div class="card">
      {#each expense.comments ?? [] as c (c.id)}
        <div class="cmt" class:cmt--me={c.userId === store.me}>
          <b>{store.name(c.userId)}</b>
          <span class="cmt__body">{c.body}</span>
          <span class="meta" style="margin:0">{time(c.createdAt)}</span>
        </div>
      {:else}
        <p class="meta" style="margin:2px 2px 10px">No comments yet.</p>
      {/each}
      {#if !store.view?.readOnly}
        <form class="cmt__form" onsubmit={(e) => { e.preventDefault(); void send(); }}>
          <input class="inp" type="text" placeholder="Add a comment" aria-label="Add a comment" maxlength="2000" bind:value={draft} />
          <button type="submit" class="link" disabled={store.busy || !draft.trim()}>Send</button>
        </form>
      {/if}
    </div>

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
  </Sheet>
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
  .strip {
    display: flex;
    gap: 10px;
    overflow-x: auto;
    padding: 2px 0 12px;
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
    font-size: 11px;
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
  .pthumb {
    width: 30px;
    height: 40px;
    border-radius: 7px;
    border: 1px solid var(--border);
    padding: 0;
    overflow: hidden;
    background: var(--surface);
    cursor: pointer;
    flex: none;
  }
  .pthumb img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .pthumb--add {
    border: 1.3px dashed var(--blue);
    color: var(--blue);
    font-size: 15px;
    background: none;
  }
  .cmt {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 8px 10px;
    margin-bottom: 8px;
    border-radius: 12px;
    background: var(--surface);
    font-size: 13.5px;
  }
  .cmt--me {
    background: var(--blue-tint);
  }
  .cmt b {
    font-size: 12.5px;
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
