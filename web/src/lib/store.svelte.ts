/**
 * The whole app's state, and the only place that talks to `api.ts`.
 *
 * Three rules from the spec are enforced here rather than in the screens, because a screen is easy
 * to forget and this file is not:
 *
 * - **No optimistic writes** (§6). Every mutation awaits the server and then re-reads the bill.
 *   Pretending a claim landed when it did not is worse than a spinner: the guest walks away believing
 *   they are done.
 * - **Poll, never subscribe** (§6). The realtime doorbell is membership-RLS-scoped and an anonymous
 *   browser cannot subscribe to it. 5s while the tab is visible, immediately on focus, never hidden.
 * - **Money is computed here, from rows** (§2.10). The server never returns a total.
 */

import { api, ApiError, type BillHeader, type BillResponse, type Candidate, type EditKind } from './api.ts';
import { clearSessionToken, saveSessionToken, tokenForBill, tokenForGroup } from './session.ts';
import { splitBill, toSplitInput, type BillResult, type ItemStatus } from './money/index.ts';
import { buildChanges, buildLines, claimProgress, type ChangeView, type LineView } from './lines.ts';
import { capture } from './analytics.ts';

export type Phase =
  | 'loading'
  | 'pick_name'
  | 'name_entry'
  | 'welcome_back'
  | 'claiming'
  | 'summary'
  | 'expired'
  | 'gone'
  | 'claimed_elsewhere'
  | 'no_token';

const POLL_MS = 5_000;

export class ClaimStore {
  billToken = $state('');
  phase = $state<Phase>('loading');
  header = $state<BillHeader | null>(null);
  candidates = $state<Candidate[]>([]);
  identity = $state<{ userId: string; name: string } | null>(null);
  bill = $state<BillResponse | null>(null);

  /** A failed write or read the guest can retry. Never a silent failure, never a fake success. */
  error = $state<string | null>(null);
  /** Set while a write is in flight, so buttons can show they are working. */
  busy = $state(false);
  /** Set once "I'm done" has been sent, so the summary can offer to undo it. */
  doneAt = $state<number | null>(null);

  sessionToken = $state<string | null>(null);

  #pollTimer: ReturnType<typeof setInterval> | null = null;
  #onVisibility: (() => void) | null = null;

  /** The money engine's verdict on the current rows. Recomputed whenever the bill changes.
   *
   *  Straight off the rows, with nothing folded in. A guest's edit is a real line the moment she makes
   *  it (§2.7), so E19 holds by construction: her total includes it because it is there. The earlier
   *  version synthesised provisional lines from undecided proposals, and that whole idea goes with the
   *  approval gate — synthesising one now would count the same money twice. */
  split = $derived<BillResult | null>(this.bill ? splitBill(toSplitInput(this.bill)) : null);

  itemStatus = $derived<Record<string, ItemStatus>>(
    Object.fromEntries((this.split?.items ?? []).map((i) => [i.itemId, i.status])),
  );

  lines = $derived<LineView[]>(
    this.bill
      ? buildLines({
          bill: this.bill,
          myUserId: this.identity?.userId ?? null,
          perItemByUser: this.split?.perItemByUser ?? {},
          itemStatus: this.itemStatus,
        })
      : [],
  );

  progress = $derived(claimProgress(this.lines));

  /** The bill's change log, oldest first. Empty on the overwhelming majority of bills. */
  changes = $derived<ChangeView[]>(this.bill ? buildChanges(this.bill, this.identity?.userId ?? null) : []);

  /** Set when this guest just lost a payer race (E25), so the page can say who won. */
  payerNotice = $state<string | null>(null);

  /** Whether the person holding this browser is currently the bill's payer. */
  get iAmPayer(): boolean {
    return Boolean(this.identity && this.bill?.payer.userId === this.identity.userId);
  }

  /** What I owe, broken into parts, so the summary can explain the number instead of asserting it. */
  myBreakdown = $derived(
    this.identity && this.split ? (this.split.breakdownByUser[this.identity.userId] ?? null) : null,
  );

  myTotalSubunits = $derived(
    this.identity && this.split ? (this.split.owedByUser[this.identity.userId] ?? 0) : 0,
  );

  currency = $derived(this.bill?.expense.currency ?? this.header?.currency ?? 'USD');

  /** Every screen offers this (E4); E5 — one phone passed around a table — is why it is never hidden. */
  get canRelease(): boolean {
    return Boolean(this.sessionToken && this.identity);
  }

  get peopleOnBill(): Array<{ userId: string; name: string }> {
    return (this.bill?.participants ?? []).map((p) => ({ userId: p.userId, name: p.name }));
  }

  // ── lifecycle ────────────────────────────────────────────────────────────────────────────

  async start(billToken: string | null): Promise<void> {
    if (!billToken) {
      this.phase = 'no_token';
      return;
    }
    this.billToken = billToken;
    await this.resolve();
  }

  /**
   * The landing branch (§3.1), and the two-step dance §2.2 needs: a stored token filed under this
   * bill resolves in one call; otherwise resolve cold, learn the group name, and try that group's
   * token. A brand-new guest costs one call either way.
   */
  /** Guards claim_page_viewed to once per store instance — resolve() re-runs after a lost claim race
   *  and after release(), and neither is a fresh page view. */
  #viewed = false;

  async resolve(): Promise<void> {
    if (!this.#viewed) {
      this.#viewed = true;
      capture('claim_page_viewed');
    }
    const stored = tokenForBill(this.billToken);
    try {
      let res = await api.resolve(this.billToken, stored);
      if (!stored && res.state !== 'welcome_back') {
        const byGroup = tokenForGroup(res.header.groupName);
        if (byGroup) {
          const second = await api.resolve(this.billToken, byGroup);
          if (second.state === 'welcome_back' || second.state === 'claimed_elsewhere') {
            res = second;
            this.sessionToken = byGroup;
          }
        }
      } else if (stored) {
        this.sessionToken = stored;
      }

      this.header = res.header;
      this.error = null;
      switch (res.state) {
        case 'welcome_back':
          this.identity = res.identity;
          if (this.sessionToken) saveSessionToken(this.billToken, res.header.groupName, this.sessionToken);
          this.phase = 'welcome_back';
          break;
        case 'pick_name':
          this.candidates = res.candidates;
          this.phase = 'pick_name';
          capture('web_candidates_shown', { candidate_count: res.candidates.length });
          break;
        case 'name_entry':
          this.candidates = [];
          this.phase = 'name_entry';
          break;
        case 'claimed_elsewhere':
          // E10: the payer merged this placeholder into a real account mid-claim. Her claims stand;
          // this browser just stops being her.
          this.phase = 'claimed_elsewhere';
          break;
      }
    } catch (e) {
      this.#handle(e);
    }
  }

  /** Enters the claim list and starts polling. */
  async enterClaiming(): Promise<void> {
    this.phase = 'claiming';
    await this.refresh();
    this.startPolling();
  }

  async refresh(): Promise<void> {
    try {
      const bill = await api.bill(this.billToken);
      this.bill = bill;
      const me = this.identity?.userId;
      this.doneAt = me ? (bill.participants.find((p) => p.userId === me)?.doneAt ?? null) : null;
      this.error = null;
    } catch (e) {
      this.#handle(e);
    }
  }

  startPolling(): void {
    this.stopPolling();
    this.#pollTimer = setInterval(() => {
      if (document.visibilityState === 'visible') void this.refresh();
    }, POLL_MS);
    this.#onVisibility = () => {
      if (document.visibilityState === 'visible') void this.refresh();
    };
    document.addEventListener('visibilitychange', this.#onVisibility);
    window.addEventListener('focus', this.#onVisibility);
  }

  stopPolling(): void {
    if (this.#pollTimer) clearInterval(this.#pollTimer);
    this.#pollTimer = null;
    if (this.#onVisibility) {
      document.removeEventListener('visibilitychange', this.#onVisibility);
      window.removeEventListener('focus', this.#onVisibility);
      this.#onVisibility = null;
    }
  }

  // ── identity ─────────────────────────────────────────────────────────────────────────────

  /**
   * "That's me" on the evidence list, or accepting a fuzzy-match suggestion while typing a new name.
   * First claim wins; the loser re-picks (E3). `source` is one of "pick_name_list" (the evidence-list
   * tap) or "name_entry_suggestion" (accepted mid-typing) — see ANALYTICS_PLAN_C_JOURNEY_AND_CLAIMING.md
   * §5a for why this exists: it's the anti-duplication design (one identity per real person) actually
   * being measured.
   */
  async claimPlaceholder(placeholderUserId: string, source: 'pick_name_list' | 'name_entry_suggestion'): Promise<{ won: boolean }> {
    return this.#write(async () => {
      const res = await api.claimPlaceholder(this.billToken, placeholderUserId);
      if (!res.won) {
        this.error = 'Someone already claimed that name. Pick another, or add a new one.';
        capture('web_claim_race_lost');
        await this.resolve();
        return { won: false };
      }
      this.#adopt(res.sessionToken, res.userId, res.name, res.header);
      capture('web_identity_resolved', { outcome: 'claimed_placeholder', source });
      return { won: true };
    }, { won: false });
  }

  /**
   * Submitting a typed name has three outcomes and the caller has to handle all of them: blocked on
   * an exact duplicate (§2.4), a fuzzy suggestion to consider (§3.2), or created.
   */
  async submitName(name: string, force = false): Promise<
    { kind: 'blocked'; suggestions: string[] } | { kind: 'suggestion'; candidate: Candidate } | { kind: 'created' }
  > {
    return this.#write(async () => {
      const res = await api.name(this.billToken, name, force);
      if ('blocked' in res) return { kind: 'blocked' as const, suggestions: res.suggestions };
      if ('suggestion' in res) return { kind: 'suggestion' as const, candidate: res.suggestion };
      this.#adopt(res.sessionToken, res.userId, name, res.header);
      // candidates.length > 0 means she was shown the evidence list and typed a new name anyway
      // ("I'm none of these"); zero means there was never a placeholder to offer her.
      capture('web_identity_resolved', {
        outcome: 'created_new',
        source: this.candidates.length > 0 ? 'pick_name_declined' : 'name_entry_direct',
      });
      return { kind: 'created' as const };
    }, { kind: 'blocked' as const, suggestions: [] });
  }

  /** Releases this browser's binding, never the placeholder — her claims stay hers (E4). */
  async release(): Promise<void> {
    const groupName = this.header?.groupName ?? '';
    if (this.sessionToken) {
      try {
        await api.release(this.billToken, this.sessionToken);
      } catch {
        // Even if the server never hears it, this browser must stop being her — that is the whole
        // point of the button, and the stale session simply expires unused.
      }
    }
    clearSessionToken(this.billToken, groupName);
    this.sessionToken = null;
    this.identity = null;
    this.bill = null;
    this.stopPolling();
    this.phase = 'loading';
    await this.resolve();
  }

  // ── claiming ─────────────────────────────────────────────────────────────────────────────

  /** Solo claim (`quantity` units) or unclaim (`0`). Writes only my own row. */
  async setClaim(itemId: string, quantity: number): Promise<void> {
    await this.#write(async () => {
      await api.claim(this.billToken, this.sessionToken!, itemId, quantity);
      if (quantity > 0) capture('claim_item_selected', { expense_id: this.bill?.expense.id, item_id: itemId });
      await this.refresh();
    }, undefined);
  }

  /** Join a line someone else claimed. Applies instantly, is attributed, and is reversible (§2.7). */
  async join(itemId: string, portionId: string | null, overClaimAck = false): Promise<'ok' | 'overclaimed'> {
    return this.#write(async () => {
      try {
        await api.join(this.billToken, this.sessionToken!, itemId, portionId, overClaimAck);
      } catch (e) {
        // E14: the last unit went while the sheet was open. Not an error — an offer to add anyway.
        if (e instanceof ApiError && e.code === 'OVERCLAIMED') return 'overclaimed' as const;
        throw e;
      }
      await this.refresh();
      return 'ok' as const;
    }, 'overclaimed' as const);
  }

  /**
   * The share sheet's commit (§3.4): me plus everyone I named, all on one portion.
   *
   * `newNames` mints a placeholder for someone who isn't here — subject to the same §2.4 uniqueness
   * block, which is why it goes through the `name` endpoint. **Wart:** that endpoint also mints a
   * session for the new person, and this browser throws it away, so each name added this way leaves
   * one unusable `web_sessions` row behind. Harmless (the token is random and never leaves this
   * response) but not clean; the fix is a placeholder-creation endpoint that does not open a session.
   */
  async shareLine(itemId: string, memberUserIds: string[], newNames: string[] = []): Promise<'ok' | 'overclaimed' | 'blocked'> {
    return this.#write(async () => {
      const ids = [...memberUserIds];
      for (const raw of newNames) {
        const res = await api.name(this.billToken, raw);
        if ('blocked' in res) return 'blocked' as const;
        if ('suggestion' in res) {
          // A fuzzy near-match on someone else's name: take the existing person rather than mint a
          // near-duplicate. Blocking is never right here (§3.2), but neither is a second "Purty".
          ids.push(res.suggestion.userId);
          continue;
        }
        ids.push(res.userId);
      }
      try {
        await api.share(this.billToken, this.sessionToken!, itemId, ids);
      } catch (e) {
        if (e instanceof ApiError && e.code === 'OVERCLAIMED') return 'overclaimed' as const;
        throw e;
      }
      await this.refresh();
      return 'ok' as const;
    }, 'overclaimed' as const);
  }

  /** Take myself off a line, whether I got there by claiming or by joining (§2.7, E12). */
  async leave(itemId: string): Promise<void> {
    await this.#write(async () => {
      await api.leave(this.billToken, this.sessionToken!, itemId);
      await this.refresh();
    }, undefined);
  }

  /**
   * Change a line. It applies, is attributed, and the payer is told; anyone can undo it (§2.7).
   *
   * An `ADD` also **claims** the new line for the person who added it, in the same call. "The scan
   * missed it? Add it and claim it" is what the sheet promises, and someone typing in the dessert they
   * ate has already told us they ate it — leaving the line unclaimed would make them say it twice. It
   * is a normal claim afterwards, so they can drop it or share it like any other.
   */
  async editLine(edit: {
    kind: EditKind;
    itemId?: string;
    label?: string;
    quantity?: number;
    unitPriceSubunits?: number;
  }): Promise<boolean> {
    return this.#write(async () => {
      const res = await api.edit(this.billToken, this.sessionToken!, edit);
      if (edit.kind === 'ADD' && res.itemId) {
        await api.claim(this.billToken, this.sessionToken!, res.itemId, edit.quantity ?? 1);
      }
      await this.refresh();
      return true;
    }, false);
  }

  /** Take a change back. Anyone on the bill may, and a second undo of the same change does nothing. */
  async undoChange(editId: string): Promise<void> {
    await this.#write(async () => {
      await api.undo(this.billToken, this.sessionToken!, editId);
      capture('claim_undone', { expense_id: this.bill?.expense.id });
      await this.refresh();
    }, undefined);
  }

  /**
   * "I paid for this" (§3.8, E24). The base is the `split_version` this page last read, and the server
   * decides against it: two guests both tapping this is E25, and the causally stale one is *told* who
   * the payer is rather than silently losing. That is not an error path, so it does not go through
   * `#handle` — it sets a notice and re-reads.
   */
  async setPayer(userId: string): Promise<boolean> {
    const base = this.bill?.expense.splitVersion;
    if (base === undefined) return false;
    return this.#write(async () => {
      const res = await api.setPayer(this.billToken, this.sessionToken!, userId, base);
      this.payerNotice = res.ok
        ? null
        : `${res.payerName ?? 'Someone else'} is the payer now. Nothing you claimed has changed.`;
      await this.refresh();
      return res.ok;
    }, false);
  }

  /** A nudge-silencer, not a lock: the guest can come back and change anything (§3.3, E31). */
  async setDone(done: boolean): Promise<void> {
    await this.#write(async () => {
      await api.done(this.billToken, this.sessionToken!, done);
      if (done) {
        const me = this.identity?.userId;
        const itemCount = me ? this.bill?.claims.filter((c) => c.user_id === me && c.quantity > 0).length ?? 0 : 0;
        capture('claim_submitted', { expense_id: this.bill?.expense.id, item_count: itemCount });
      }
      await this.refresh();
      this.phase = done ? 'summary' : 'claiming';
    }, undefined);
  }

  // ── internals ────────────────────────────────────────────────────────────────────────────

  #adopt(sessionToken: string, userId: string, name: string, header: BillHeader): void {
    this.sessionToken = sessionToken;
    this.identity = { userId, name };
    this.header = header;
    saveSessionToken(this.billToken, header.groupName, sessionToken);
  }

  /** One place where every write reports failure, so no screen can quietly swallow one. */
  async #write<T>(run: () => Promise<T>, fallback: T): Promise<T> {
    this.busy = true;
    this.error = null;
    try {
      return await run();
    } catch (e) {
      this.#handle(e);
      return fallback;
    } finally {
      this.busy = false;
    }
  }

  #handle(e: unknown): void {
    if (e instanceof ApiError && e.isTerminal) {
      // E26: expiry mid-session. Writes stop; the amount and the way to pay it stay (frame 9).
      this.stopPolling();
      this.phase = e.code === 'EXPIRED' ? 'expired' : 'gone';
      return;
    }
    this.error =
      e instanceof ApiError && e.code === 'NETWORK'
        ? "That didn't send. Check your connection and try again."
        : "That didn't save. Try again.";
  }
}

export const store = new ClaimStore();
