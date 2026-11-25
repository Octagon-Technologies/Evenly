/**
 * The web group page's state (docs/features/web-group-page.md): the whole group, from the guest's seat,
 * after the `/j/` page has decided who they are.
 *
 * Reads are one `group-view` call; every write goes through the `web-claim` group actions and is followed
 * by a fresh read. No optimistic writes (WEB_CLAIM_SPEC.md §6): `busy` while a write is in flight, and a
 * write that did not land says so instead of showing a number that is not true.
 */

import {
  ApiError,
  groupApi,
  type ExpenseEdit,
  type GroupExpense,
  type GroupView,
  type GroupWriteResult,
  type NewExpense,
  type ScanReceipt,
  type ScanSummary,
} from './api.ts';
import { toScanPage } from './receiptImage.ts';
import { buildBilateralBalances, myDebts, type Debt, type OpenShare } from './money/balances.ts';
import { capture } from './analytics.ts';

export type GroupPhase = 'loading' | 'ready' | 'gone' | 'failed';

/** The viewer's position on one expense, the same rule as the app's `personalStake`. */
export interface Stake {
  owedToMe: boolean;
  subunits: number;
}

export class GroupStore {
  phase = $state<GroupPhase>('loading');
  view = $state<GroupView | null>(null);
  busy = $state(false);

  readonly token: string;
  readonly sessionToken: string;

  constructor(token: string, sessionToken: string) {
    this.token = token;
    this.sessionToken = sessionToken;
  }

  me = $derived(this.view?.me.userId ?? '');

  names = $derived(new Map((this.view?.members ?? []).map((m) => [m.userId, m.name])));

  debts = $derived.by<{ iOwe: Debt[]; owedToMe: Debt[] }>(() => {
    const open: OpenShare[] = [];
    for (const e of this.view?.expenses ?? []) {
      if (!e.payerUserId) continue;
      for (const s of e.shares) {
        if (s.userId === e.payerUserId) continue;
        open.push({ payerUserId: e.payerUserId, participantUserId: s.userId, currency: e.currency, remainingSubunits: s.remainingSubunits });
      }
    }
    return myDebts(buildBilateralBalances(open), this.me);
  });

  /** Itemized bills that list the viewer and that they have not claimed anything on yet (scenario 4). */
  waiting = $derived(
    (this.view?.expenses ?? []).filter(
      (e) => e.splitMode === 'ITEMIZED' && this.view?.billTokens[e.id] && !e.iClaimed && !e.iAmDone,
    ),
  );

  name(userId: string | null | undefined): string {
    if (!userId) return 'Someone';
    if (userId === this.me) return 'You';
    return this.names.get(userId) ?? 'Someone';
  }

  /** Paid for it, holds a share of it, or was listed on the bill: drawn as the left-edge strip. */
  involved(e: GroupExpense): boolean {
    const me = this.me;
    return e.payerUserId === me || e.shares.some((s) => s.userId === me) || e.participantIds.includes(me);
  }

  stake(e: GroupExpense): Stake | null {
    const me = this.me;
    if (e.shares.length === 0) return null;
    if (e.payerUserId === me) {
      const owed = e.shares.filter((s) => s.userId !== me).reduce((t, s) => t + Math.max(s.remainingSubunits, 0), 0);
      return owed > 0 ? { owedToMe: true, subunits: owed } : null;
    }
    const mine = e.shares.filter((s) => s.userId === me).reduce((t, s) => t + Math.max(s.remainingSubunits, 0), 0);
    return mine > 0 ? { owedToMe: false, subunits: mine } : null;
  }

  /** Settled once the shares cover the bill and everyone but the payer has paid it all back. */
  settled(e: GroupExpense): boolean {
    if (e.shares.length === 0) return false;
    if (e.shares.reduce((t, s) => t + s.owedSubunits, 0) < e.amountSubunits) return false;
    return e.shares.filter((s) => s.userId !== e.payerUserId).every((s) => s.remainingSubunits <= 0);
  }

  billToken(e: GroupExpense): string | null {
    return this.view?.billTokens[e.id] ?? null;
  }

  editsFor(expenseId: string) {
    return (this.view?.edits ?? []).filter((x) => x.expenseId === expenseId);
  }

  async load(): Promise<void> {
    if (!this.view) this.phase = 'loading';
    try {
      this.view = await groupApi.view(this.token, this.sessionToken);
      this.phase = 'ready';
    } catch (e) {
      if (e instanceof ApiError && (e.isTerminal || e.code === 'SESSION_REQUIRED')) this.phase = 'gone';
      else if (!this.view) this.phase = 'failed';
    }
  }

  /** "I paid Joe", across bills or for one ([expenseId]). Returns null when it landed, or what to tell the guest. */
  async pay(debt: Debt, amountSubunits: number, expenseId: string | null = null): Promise<string | null> {
    return this.#write(async () => {
      // A debt owed TO the guest is "they paid me": the guest records money they received.
      const received = debt.creditorUserId === this.me;
      const party = received ? { fromUserId: debt.debtorUserId } : { toUserId: debt.creditorUserId };
      const res = await groupApi.pay(this.token, this.sessionToken, party, debt.currency, amountSubunits, expenseId);
      if (!res.ok) return refusal(res);
      capture(received ? 'group_web_payment_received' : 'group_web_paid', {});
      return null;
    });
  }

  async create(expense: NewExpense): Promise<{ problem: string | null; billToken: string | null }> {
    let billToken: string | null = null;
    const problem = await this.#write(async () => {
      const res = await groupApi.create(this.token, this.sessionToken, expense);
      if (!res.ok) return refusal(res);
      billToken = res.billToken ?? null;
      capture('group_web_expense_added', { mode: expense.splitMode });
      return null;
    });
    return { problem, billToken };
  }

  async edit(edit: ExpenseEdit): Promise<string | null> {
    return this.#write(async () => {
      const res = await groupApi.edit(this.token, this.sessionToken, edit);
      if (!res.ok) return refusal(res);
      capture('group_web_expense_edited', {});
      return null;
    });
  }

  async undo(editId: string): Promise<string | null> {
    return this.#write(async () => {
      const res = await groupApi.undo(this.token, this.sessionToken, editId);
      if (!res.ok) return refusal(res);
      return null;
    });
  }

  /**
   * Reads a receipt for the add-expense form. Nothing is saved: the form fills in and the guest checks it.
   * Every way it can fail ends in a sentence that says to type it in, so a scan is never a dead end.
   */
  async scan(
    files: File[],
    mode: 'summary' | null,
  ): Promise<{ summary?: ScanSummary; receipt?: ScanReceipt; verified?: boolean; problem?: string }> {
    this.busy = true;
    try {
      const pages = (await Promise.all(files.slice(0, 4).map(toScanPage))).filter((p) => p !== null);
      if (pages.length === 0) return { problem: "That file isn't a photo or a PDF. Try another, or type it in." };
      const res = await groupApi.scan(this.token, this.sessionToken, pages, mode);
      capture('group_web_receipt_scanned', { mode: mode ?? 'items', pages: pages.length });
      if (res.configured === false) return { problem: "Receipt scanning isn't available right now. Type it in instead." };
      if (res.noReceipt || (!res.summary && !res.receipt)) {
        return { problem: "Couldn't find a receipt in that. Try a clearer photo, or type it in." };
      }
      return { summary: res.summary, receipt: res.receipt, verified: res.verified };
    } catch (e) {
      if (e instanceof ApiError && e.status === 429) {
        return { problem: "That's a lot of scans for now. Try again later, or type it in." };
      }
      if (e instanceof ApiError && e.code === 'NETWORK') {
        return { problem: "That didn't go through. Check your connection and try again." };
      }
      return { problem: "Couldn't read that one. Try a clearer photo, or type it in." };
    } finally {
      this.busy = false;
    }
  }

  async #write(run: () => Promise<string | null>): Promise<string | null> {
    this.busy = true;
    try {
      const problem = await run();
      // Reload either way: a refusal like STALE means the page is showing old numbers.
      await this.load();
      return problem;
    } catch (e) {
      if (e instanceof ApiError && e.code === 'CLAIMED_ELSEWHERE') {
        await this.load();
        return 'This name is linked to an Evenly account now. Make changes in the app.';
      }
      if (e instanceof ApiError && e.isTerminal) {
        this.phase = 'gone';
        return null;
      }
      return e instanceof ApiError && e.code === 'NETWORK'
        ? "That didn't go through. Check your connection and try again."
        : "That didn't go through. Try again.";
    } finally {
      this.busy = false;
    }
  }
}

function refusal(res: GroupWriteResult): string {
  switch (res.reason) {
    case 'ABOVE_CEILING':
      return "That's more than is still owed.";
    case 'NOT_OWED':
      return 'Nothing is owed between you two right now.';
    case 'STALE':
      return 'Someone changed this while you were editing. These are the latest numbers.';
    case 'CHANGED_SINCE':
      return "This can't be undone: it was changed again after that.";
    case 'UNSUPPORTED':
      return 'This one is split by items. Change the items on the bill itself.';
    case 'NAME_TAKEN':
      return `${res.detail ?? 'That name'} is already in the group. Pick them from the list instead.`;
    case 'INVALID':
      return capitalise(res.detail ?? 'Something is missing.');
    case 'GONE':
      return 'That expense was deleted.';
    default:
      return "That didn't go through. Try again.";
  }
}

function capitalise(s: string): string {
  return s ? s[0].toUpperCase() + s.slice(1) + (s.endsWith('.') ? '' : '.') : s;
}
