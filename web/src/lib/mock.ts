/**
 * A canned bill, in memory, for walking the flow without a live link: `npm run dev` then
 * `http://localhost:5177/?b=mockmockmockmock&mock`.
 *
 * **Dev only.** `main.ts` imports this behind `import.meta.env.DEV`, so it is dead code in a build.
 * It is a UI fixture, not a second implementation: it stores rows and hands them back, and every
 * number the screens show still comes from the real money engine.
 *
 * Query flags, so the states that are hard to reach on purpose can still be walked:
 *   `?mock`               → an unknown visitor, group has placeholders → frame 1
 *   `?mock&as=new`        → no placeholders at all                     → frame 2
 *   `?mock&as=purity`     → a recognised returning guest               → frame 3
 *   `?mock&state=expired` → legacy expiry or temporary refusal          → frame 9
 *   `?mock&state=gone`    → revoked / deleted
 *   `?mock&race=payer`    → every "I paid" loses the causal race (spec E25)
 *
 * The group invite page takes the same fixture: `/j/01a16973-8c78-7001-9dd8-3bdbc21b7339?mock`
 * (add `&as=new` for an empty list, `&as=purity` for a returning guest). Typing "Mary" hits the
 * exact-duplicate block; "Purityy" gets the "did you mean her?" suggestion. After picking a name the page
 * becomes the group page (docs/features/web-group-page.md), served by the `group-*` cases below.
 */

import { ApiError, setTransport, type ApiErrorCode } from './api.ts';

interface MockItem {
  id: string;
  label: string;
  quantity: number;
  line_total_subunits: number;
  sort_order: number;
}

const NAMES: Record<string, string> = {
  'u-andrew': 'Andrew',
  'u-mary': 'Mary',
  'u-bob': 'Bob',
  'u-steve': 'Steve',
  'u-mo': 'Mo',
  'u-purity': 'Purity',
  'u-jane': 'Jane',
  'u-kim': 'Kim',
};

/** Mutable, because a guest's edit APPLIES (spec §2.7) — the fixture has to move with it or the mock
 *  walkthrough would still be showing the model this feature replaced. */
const ITEMS: MockItem[] = [
  { id: 'i-chicken-1', label: 'Chicken', quantity: 1, line_total_subunits: 1800, sort_order: 1 },
  { id: 'i-chicken-2', label: 'Chicken', quantity: 2, line_total_subunits: 3600, sort_order: 2 },
  { id: 'i-juice', label: 'Juice', quantity: 1, line_total_subunits: 450, sort_order: 3 },
  { id: 'i-soda', label: 'Soda', quantity: 1, line_total_subunits: 400, sort_order: 4 },
  { id: 'i-edamame', label: 'Edamame', quantity: 3, line_total_subunits: 1350, sort_order: 5 },
  { id: 'i-katsu', label: 'Katsu curry', quantity: 2, line_total_subunits: 3200, sort_order: 6 },
  { id: 'i-ramen', label: 'Tonkotsu ramen', quantity: 3, line_total_subunits: 4200, sort_order: 7 },
  { id: 'i-gyoza', label: 'Gyoza', quantity: 2, line_total_subunits: 1400, sort_order: 8 },
];

interface Claim {
  id: string;
  item_id: string;
  user_id: string;
  quantity: number;
}
interface Share {
  id: string;
  item_id: string;
  user_id: string;
  portion_id: string | null;
  quantity: number;
  added_by: string | null;
}

const state = {
  claims: [
    { id: 'c1', item_id: 'i-chicken-1', user_id: 'u-mary', quantity: 1 },
    { id: 'c2', item_id: 'i-chicken-2', user_id: 'u-bob', quantity: 1 },
    { id: 'c3', item_id: 'i-chicken-2', user_id: 'u-steve', quantity: 1 },
    { id: 'c4', item_id: 'i-edamame', user_id: 'u-andrew', quantity: 1 },
    { id: 'c5', item_id: 'i-edamame', user_id: 'u-mo', quantity: 1 },
  ] as Claim[],
  shares: [] as Share[],
  items: [...ITEMS],
  /** The bill's change log. Named for the wire field, which is named for the table. */
  pendingEdits: [] as Array<Record<string, unknown>>,
  /** The causal Zone-2 version a payer write is decided against (spec §5.5, E25). */
  splitVersion: 1,
  participants: ['u-andrew', 'u-mary', 'u-bob', 'u-steve', 'u-mo'],
  doneAt: null as number | null,
  payerUserId: 'u-andrew',
  me: null as string | null,
  /** The guest's payments back to the payer (one-time-splits B4). Owed is fixed: a walkthrough, not math. */
  myPayments: [] as Array<{ id: string; amountSubunits: number; hereSubunits: number; app: string | null; settledAt: number }>,
  myOwedSubunits: 2105,
  seq: 0,
};

function paymentsView() {
  const paid = state.myPayments.reduce((n, p) => n + p.hereSubunits, 0);
  return {
    owedSubunits: state.myOwedSubunits,
    remainingSubunits: Math.max(state.myOwedSubunits - paid, 0),
    payments: [...state.myPayments].sort((a, b) => b.settledAt - a.settledAt),
  };
}

function flag(name: string): string | null {
  return new URLSearchParams(location.search).get(name);
}

function nextId(prefix: string): string {
  state.seq += 1;
  return `${prefix}-${state.seq}`;
}

function header() {
  return {
    groupName: 'Ramen night',
    title: 'Tuk Tuk Kitchen',
    currency: 'USD',
    totalSubunits: ITEMS.reduce((s, i) => s + i.line_total_subunits, 0) + 1340 + 2800,
    itemCount: ITEMS.length,
    claimedCount: new Set(state.claims.map((c) => c.item_id)).size,
    participantCount: state.participants.length,
  };
}

/** Throws the same `ApiError` the real transport does, so the error paths are exercised, not bypassed. */
function fail(code: ApiErrorCode, status: number): never {
  throw new ApiError(code, status);
}

function ensureParticipant(userId: string) {
  if (!state.participants.includes(userId)) state.participants.push(userId);
}

/** The one rule this fixture has to model faithfully: joining converts a solo claim into a portion. */
function joinPortion(itemId: string, userId: string, portionId: string | null): string {
  state.claims = state.claims.filter((c) => !(c.item_id === itemId && c.user_id === userId));
  state.shares = state.shares.filter((s) => !(s.item_id === itemId && s.user_id === userId));

  let pid = portionId;
  if (!pid) {
    const existing = state.shares.find((s) => s.item_id === itemId && s.portion_id);
    if (existing) pid = existing.portion_id;
  }
  if (!pid) {
    const solo = state.claims.find((c) => c.item_id === itemId);
    pid = nextId('p');
    if (solo) {
      state.claims = state.claims.filter((c) => c !== solo);
      for (let i = 0; i < solo.quantity; i++) {
        state.shares.push({
          id: nextId('s'),
          item_id: itemId,
          user_id: solo.user_id,
          portion_id: pid,
          quantity: 1,
          added_by: solo.user_id,
        });
      }
    }
  }
  const quantity = state.shares.find((s) => s.portion_id === pid)?.quantity ?? 1;
  state.shares.push({
    id: nextId('s'),
    item_id: itemId,
    user_id: userId,
    portion_id: pid,
    quantity,
    added_by: state.me,
  });
  return pid;
}


// ── The web group page fixture: a few bills with real share rows, so balances come from the real math. ──

interface MockShare { id: string; userId: string; owedSubunits: number; exactSubunits: number | null; paid: number }
interface MockExpense {
  id: string; title: string; date: string; createdAt: number; amountSubunits: number; currency: string;
  payerUserId: string; splitMode: string; splitVersion: number; createdBy: string; shares: MockShare[];
  participantIds: string[]; lineCount: number;
}

function evenShares(amount: number, ids: string[]): MockShare[] {
  const sorted = [...ids].sort();
  const base = Math.floor(amount / sorted.length);
  return sorted.map((id, i) => ({
    id: `sh-${Math.random().toString(36).slice(2)}`, userId: id,
    owedSubunits: base + (i < amount % sorted.length ? 1 : 0), exactSubunits: null, paid: 0,
  }));
}

const groupState = {
  expenses: [
    { id: 'gx-ramen', title: 'Ramen night', date: '2026-11-23', createdAt: 5, amountSubunits: 9640, currency: 'USD',
      payerUserId: 'u-andrew', splitMode: 'ITEMIZED', splitVersion: 1, createdBy: 'u-andrew', shares: [],
      participantIds: ['u-andrew', 'u-mary', 'u-purity', 'u-mo', 'u-kim'], lineCount: 6 },
    { id: 'gx-taco', title: 'Taco night', date: '2026-11-23', createdAt: 4, amountSubunits: 6400, currency: 'USD',
      payerUserId: 'u-steve', splitMode: 'EVEN', splitVersion: 1, createdBy: 'u-steve',
      shares: evenShares(6400, ['u-steve', 'u-bob', 'u-mary']), participantIds: [], lineCount: 0 },
    { id: 'gx-groc', title: 'Groceries', date: '2026-11-21', createdAt: 3, amountSubunits: 9360, currency: 'USD',
      payerUserId: 'u-mary', splitMode: 'EVEN', splitVersion: 1, createdBy: 'u-mary',
      shares: evenShares(9360, ['u-mary', 'u-purity', 'u-mo', 'u-kim']), participantIds: [], lineCount: 0 },
    { id: 'gx-uber', title: 'Airport Uber', date: '2026-11-18', createdAt: 2, amountSubunits: 4960, currency: 'USD',
      payerUserId: 'u-andrew', splitMode: 'EVEN', splitVersion: 1, createdBy: 'u-andrew',
      shares: evenShares(4960, ['u-andrew', 'u-purity', 'u-mo', 'u-kim']), participantIds: [], lineCount: 0 },
  ] as MockExpense[],
  edits: [] as Array<{ id: string; expenseId: string; actorUserId: string; before: unknown; after: unknown; createdAt: number; undone: boolean; snapshot: MockExpense }>,
};

function snapshotOf(e: MockExpense) {
  return {
    title: e.title, amount: e.amountSubunits, payer: e.payerUserId, split_mode: e.splitMode,
    shares: e.shares.map((s) => ({ id: s.id, user_id: s.userId, owed: s.owedSubunits, exact: s.exactSubunits })),
  };
}

function groupView(me: string) {
  return {
    header: { groupName: 'Tulum trip', emoji: '🏝️', baseCurrency: 'USD' },
    me: { userId: me, name: NAMES[me] ?? 'You' },
    readOnly: false,
    billTokens: groupState.expenses.some((e) => e.id === 'gx-ramen' && e.participantIds.includes(me)) ? { 'gx-ramen': 'mockmockmockmock' } : {},
    members: Object.entries(NAMES).map(([userId, name]) => ({ userId, name, isPlaceholder: userId !== 'u-andrew' })),
    expenses: groupState.expenses.map((e) => ({
      ...e, payerOutsideName: null, categoryId: null, iClaimed: false, iAmDone: false,
      shares: e.shares.map((s) => ({ userId: s.userId, owedSubunits: s.owedSubunits, exactSubunits: s.exactSubunits,
        remainingSubunits: s.userId === e.payerUserId ? 0 : s.owedSubunits - s.paid })),
    })),
    payments: [],
    edits: groupState.edits.map(({ snapshot: _s, ...rest }) => rest),
  };
}

function peopleIds(people: Array<{ userId?: string; newName?: string; exactSubunits?: number }>): Array<{ id: string; exact?: number }> {
  return people.map((p) => {
    let id = p.userId;
    if (!id && p.newName) {
      id = `u-${p.newName.toLowerCase()}`;
      NAMES[id] = p.newName;
    }
    return { id: id!, exact: p.exactSubunits };
  });
}

function sharesFor(mode: string, amount: number, people: Array<{ id: string; exact?: number }>): MockShare[] {
  if (mode === 'EXACT') {
    return people.map((p) => ({ id: `sh-${Math.random().toString(36).slice(2)}`, userId: p.id, owedSubunits: p.exact ?? 0, exactSubunits: p.exact ?? 0, paid: 0 }));
  }
  return evenShares(amount, people.map((p) => p.id));
}

export function installMockTransport(): void {
  const as = flag('as');
  const forced = flag('state');
  if (as === 'purity') {
    state.me = 'u-purity';
    ensureParticipant('u-purity');
  }

  setTransport(async (action, body) => {
    await new Promise((r) => setTimeout(r, 140)); // enough latency to see a spinner
    if (forced === 'expired') fail('EXPIRED', 410);
    if (forced === 'gone') fail('GONE', 404);

    switch (action) {
      case 'invite-preview':
        return { groupName: 'Tulum trip' };

      case 'invite-resolve': {
        const header = { groupName: 'Tulum trip', emoji: '🏝️', memberCount: 5, memberNames: ['Andrew', 'Mary', 'Bob', 'Steve'] };
        if (state.me) return { state: 'welcome_back', header, identity: { userId: state.me, name: NAMES[state.me] } };
        if (as === 'new') return { state: 'name_entry', header };
        return {
          state: 'pick_name',
          header,
          candidates: [
            { userId: 'u-purity', name: 'Purity', evidence: [{ title: 'Airport Uber', date: '2026-07-28', amountSubunits: 1240, currency: 'USD' }] },
            { userId: 'u-mo', name: 'Mo', evidence: [] },
          ],
        };
      }

      case 'invite-name': {
        const name = String(body.name ?? '').trim();
        if (Object.values(NAMES).some((n) => n.toLowerCase() === name.toLowerCase())) {
          return { blocked: true, suggestions: [`${name} 2`, `${name} 3`] };
        }
        if (!body.force && name.toLowerCase().startsWith('purit')) {
          return { suggestion: { userId: 'u-purity', name: 'Purity', evidence: [{ title: 'Airport Uber', date: '2026-07-28', amountSubunits: 1240, currency: 'USD' }] } };
        }
        state.me = 'u-new';
        NAMES['u-new'] = name;
        return { created: true, userId: 'u-new', name, sessionToken: 'mock-session' };
      }

      case 'invite-claim': {
        const id = String(body.placeholderUserId);
        state.me = id;
        return { won: true, sessionToken: 'mock-session', userId: id, name: NAMES[id] };
      }

      case 'resolve': {
        if (state.me) {
          return { state: 'welcome_back', header: header(), identity: { userId: state.me, name: NAMES[state.me] } };
        }
        if (as === 'new') return { state: 'name_entry', header: header() };
        return {
          state: 'pick_name',
          header: header(),
          candidates: [
            {
              userId: 'u-purity',
              name: 'Purity',
              evidence: [
                { title: 'Airport Uber', date: '2026-07-28', amountSubunits: 1240 },
                { title: 'Groceries', date: '2026-07-21', amountSubunits: 810 },
              ],
            },
            {
              userId: 'u-mo',
              name: 'Mo',
              evidence: [{ title: 'Airport Uber', date: '2026-07-28', amountSubunits: 1240 }],
            },
          ],
        };
      }

      case 'name': {
        const name = String(body.name ?? '').trim();
        if (Object.values(NAMES).some((n) => n.toLowerCase() === name.toLowerCase())) {
          return { blocked: true, suggestions: [`${name} 2`, `${name} L`, `${name} D`] };
        }
        const userId = nextId('u');
        NAMES[userId] = name;
        ensureParticipant(userId);
        state.me = userId;
        return { created: true, userId, sessionToken: 'mock-session', header: header() };
      }

      case 'claim-placeholder': {
        const userId = String(body.placeholderUserId);
        state.me = userId;
        ensureParticipant(userId);
        return { won: true, sessionToken: 'mock-session', userId, name: NAMES[userId], header: header() };
      }

      case 'bill':
        return {
          expense: {
            id: 'e-ramen',
            title: 'Tuk Tuk Kitchen',
            currency: 'USD',
            taxSubunits: 1340,
            gratuitySubunits: 0,
            tipSubunits: 2800,
            tipSplitMode: 'EVEN',
            discountSubunits: 0,
            splitVersion: state.splitVersion,
          },
          items: state.items,
          claims: state.claims,
          shares: state.shares,
          pendingEdits: state.pendingEdits,
          participants: state.participants.map((userId) => ({
            userId,
            name: NAMES[userId] ?? 'Someone',
            doneAt: userId === state.me ? state.doneAt : null,
          })),
          namesByUser: NAMES,
          payer: {
            userId: state.payerUserId,
            name: NAMES[state.payerUserId] ?? null,
            app: state.payerUserId === 'u-andrew' ? 'venmo' : null,
            handle: state.payerUserId === 'u-andrew' ? '@andrew-chelimo' : null,
          },
        };

      case 'claim': {
        const itemId = String(body.itemId);
        const quantity = Number(body.quantity ?? 0);
        state.claims = state.claims.filter((c) => !(c.item_id === itemId && c.user_id === state.me));
        if (quantity > 0) {
          state.claims.push({ id: nextId('c'), item_id: itemId, user_id: state.me!, quantity });
        }
        return { ok: true };
      }

      case 'join': {
        const pid = joinPortion(String(body.itemId), state.me!, (body.portionId as string | null) ?? null);
        return { ok: true, portionId: pid, quantity: 1, members: [] };
      }

      case 'share': {
        const itemId = String(body.itemId);
        const ids = [state.me!, ...((body.memberUserIds as string[]) ?? [])];
        let pid: string | null = null;
        for (const userId of ids) pid = joinPortion(itemId, userId, pid);
        return { ok: true, portionId: pid, members: ids };
      }

      case 'leave': {
        const itemId = String(body.itemId);
        state.claims = state.claims.filter((c) => !(c.item_id === itemId && c.user_id === state.me));
        state.shares = state.shares.filter((s) => !(s.item_id === itemId && s.user_id === state.me));
        return { ok: true };
      }

      case 'edit': {
        // Applies, in one step, exactly like `apply_web_bill_edit` does server-side. previous_* are
        // captured here from the row as it stands, because they are what Undo restores.
        const id = nextId('pe');
        const kind = String(body.kind);
        const target = state.items.find((i) => i.id === body.itemId);
        const previous = target
          ? {
              previous_label: target.label,
              previous_quantity: target.quantity,
              previous_unit_price_subunits: Math.round(target.line_total_subunits / Math.max(target.quantity, 1)),
              previous_line_total_subunits: target.line_total_subunits,
            }
          : { previous_label: null, previous_quantity: null, previous_unit_price_subunits: null, previous_line_total_subunits: null };
        let itemId = (body.itemId as string | null) ?? null;

        if (kind === 'ADD') {
          const quantity = Math.max(Number(body.quantity ?? 1), 1);
          itemId = nextId('i');
          state.items = [
            ...state.items,
            {
              id: itemId,
              label: String(body.label ?? 'Item'),
              quantity,
              line_total_subunits: Number(body.unitPriceSubunits ?? 0) * quantity,
              sort_order: state.items.length + 1,
            },
          ];
        } else if (target) {
          state.items = state.items.map((i) => {
            if (i.id !== target.id) return i;
            if (kind === 'RELABEL') return { ...i, label: String(body.label ?? i.label) };
            if (kind === 'REPRICE') {
              return { ...i, line_total_subunits: Number(body.unitPriceSubunits ?? 0) * i.quantity };
            }
            const quantity = Math.max(Number(body.quantity ?? i.quantity), 1);
            const perUnit = Number(body.unitPriceSubunits ?? Math.round(i.line_total_subunits / Math.max(i.quantity, 1)));
            return { ...i, quantity, line_total_subunits: perUnit * quantity };
          });
          if (kind === 'REMOVE') {
            state.items = state.items.filter((i) => i.id !== target.id);
            state.claims = state.claims.filter((c) => c.item_id !== target.id);
            state.shares = state.shares.filter((sh) => sh.item_id !== target.id);
          }
        }

        state.splitVersion += 1;
        state.pendingEdits.push({
          id,
          item_id: itemId,
          kind,
          proposed_label: body.label ?? null,
          proposed_quantity: body.quantity ?? null,
          proposed_unit_price_subunits: body.unitPriceSubunits ?? null,
          ...previous,
          proposed_by: state.me,
          proposed_at: Date.now(),
          decided_at: Date.now(),
          decided_by: state.me,
          decision: 'APPLIED',
        });
        return { ok: true, editId: id, itemId };
      }

      case 'undo': {
        // First-undo-wins, and a second undo is a no-op rather than an error.
        const change = state.pendingEdits.find((e) => e.id === body.editId);
        if (!change || change.decision !== 'APPLIED') return { ok: true, changed: false };
        const itemId = change.item_id as string;
        if (change.kind === 'ADD') {
          state.items = state.items.filter((i) => i.id !== itemId);
          state.claims = state.claims.filter((c) => c.item_id !== itemId);
          state.shares = state.shares.filter((sh) => sh.item_id !== itemId);
        } else if (change.kind === 'REMOVE') {
          state.items = [
            ...state.items,
            {
              id: itemId,
              label: String(change.previous_label ?? 'Item'),
              quantity: Number(change.previous_quantity ?? 1),
              line_total_subunits: Number(change.previous_line_total_subunits ?? 0),
              sort_order: state.items.length + 1,
            },
          ];
        } else {
          state.items = state.items.map((i) =>
            i.id === itemId
              ? {
                  ...i,
                  label: String(change.previous_label ?? i.label),
                  quantity: Number(change.previous_quantity ?? i.quantity),
                  // The stored line total, never per-unit x quantity: rebuilding a $10.00 line over 3
                  // units from the rounded per-unit gives back $9.99.
                  line_total_subunits: Number(change.previous_line_total_subunits ?? i.line_total_subunits),
                }
              : i,
          );
        }
        state.splitVersion += 1;
        change.decision = 'UNDONE';
        change.decided_at = Date.now();
        change.decided_by = state.me;
        return { ok: true, changed: true };
      }

      case 'payer': {
        // E25: a base that is behind canonical loses and is told who the payer is. `?mock&race=payer`
        // forces that branch, because it is otherwise unreachable with one browser.
        const base = Number(body.baseSplitVersion ?? 0);
        if (flag('race') === 'payer' || base < state.splitVersion) {
          return {
            ok: false, stale: true,
            payerUserId: state.payerUserId, payerName: NAMES[state.payerUserId] ?? null,
            splitVersion: state.splitVersion,
          };
        }
        state.payerUserId = String(body.userId);
        state.splitVersion += 1;
        return {
          ok: true, stale: false,
          payerUserId: state.payerUserId, payerName: NAMES[state.payerUserId] ?? null,
          splitVersion: state.splitVersion,
        };
      }

      case 'payments':
        return paymentsView();

      case 'pay': {
        const amount = Number(body.amountSubunits);
        const room = paymentsView().remainingSubunits;
        if (!(amount > 0) || amount > room) {
          return { ok: false, reason: 'ABOVE_CEILING', ceilingSubunits: room, ...paymentsView() };
        }
        state.myPayments.push({
          id: nextId('pay'), amountSubunits: amount, hereSubunits: amount,
          app: (body.app as string | null) ?? null, settledAt: Date.now(),
        });
        return { ok: true, reason: null, ceilingSubunits: null, ...paymentsView() };
      }

      case 'payment': {
        const i = state.myPayments.findIndex((p) => p.id === body.settlementId);
        if (i < 0) return { ok: false, reason: 'GONE', ceilingSubunits: null, ...paymentsView() };
        const amount = Number(body.amountSubunits);
        const room = state.myPayments[i].hereSubunits + paymentsView().remainingSubunits;
        if (amount < 0 || amount > room) {
          return { ok: false, reason: 'ABOVE_CEILING', ceilingSubunits: room, ...paymentsView() };
        }
        if (amount === 0) state.myPayments.splice(i, 1);
        else state.myPayments[i] = { ...state.myPayments[i], id: nextId('pay'), amountSubunits: amount, hereSubunits: amount };
        return { ok: true, reason: null, ceilingSubunits: null, ...paymentsView() };
      }

      case 'done':
        state.doneAt = body.done ? Date.now() : null;
        return { ok: true };


      case 'group-view':
        return groupView(state.me ?? 'u-purity');

      case 'group-pay': {
        const me = state.me ?? 'u-purity';
        // "They paid me" (fromUserId) flips who owes whom; the guest is always one side.
        const from = body.fromUserId ? String(body.fromUserId) : me;
        const to = body.fromUserId ? me : body.toUserId;
        let left = Number(body.amountSubunits);
        const open = groupState.expenses
          .filter((e) => e.payerUserId === to && (!body.expenseId || e.id === body.expenseId))
          .flatMap((e) => e.shares.filter((s) => s.userId === from && s.owedSubunits - s.paid > 0))
          .reverse();
        const ceiling = open.reduce((t, s) => t + s.owedSubunits - s.paid, 0);
        if (left > ceiling) return { ok: false, reason: 'ABOVE_CEILING', ceilingSubunits: ceiling };
        for (const s of open) {
          const apply = Math.min(left, s.owedSubunits - s.paid);
          s.paid += apply;
          left -= apply;
        }
        return { ok: true, reason: null };
      }

      case 'group-receipt-scan':
        // A fixed read, so the scan button can be walked through without a paid model call.
        return body.mode === 'summary'
          ? { configured: true, summary: { merchant_name: 'Lula Cafe', short_title: 'Lula Cafe', total_subunits: 6420, currency: 'USD' } }
          : {
              configured: true,
              verified: true,
              receipt: {
                currency: 'USD', merchant_name: 'Lula Cafe', tax_subunits: 412, gratuity_subunits: 0, tip_subunits: 900,
                discount_subunits: 0, other_charges_subunits: 0,
                items: [
                  { label: 'Pasta', quantity: 1, line_total_subunits: 2200 },
                  { label: 'Burger', quantity: 1, line_total_subunits: 1800 },
                  { label: 'Iced tea', quantity: 2, line_total_subunits: 1108 },
                ],
              },
            };

      case 'group-expense-create': {
        const me = state.me ?? 'u-purity';
        const people = peopleIds(body.people as never);
        const items = (body.items as Array<{ lineTotalSubunits: number }> | undefined) ?? [];
        const x = body.extras as { tax: number; tip: number; gratuity: number; discount: number; other: number } | undefined;
        const extra = x ? x.tax + x.tip + x.gratuity + x.other - x.discount : 0;
        const amount = body.splitMode === 'ITEMIZED' ? items.reduce((t, i) => t + i.lineTotalSubunits, 0) + extra : Number(body.amountSubunits);
        const id = `gx-${Date.now()}`;
        groupState.expenses.unshift({
          id, title: String(body.title), date: String(body.date), createdAt: Date.now(), amountSubunits: amount, currency: 'USD',
          payerUserId: String(body.payerUserId), splitMode: String(body.splitMode), splitVersion: 1, createdBy: me,
          shares: body.splitMode === 'ITEMIZED' ? [] : sharesFor(String(body.splitMode), amount, people),
          participantIds: body.splitMode === 'ITEMIZED' ? people.map((p) => p.id) : [], lineCount: items.length,
        });
        return { ok: true, reason: null, expenseId: id, billToken: body.splitMode === 'ITEMIZED' ? 'mockmockmockmock' : null };
      }

      case 'group-expense-edit': {
        const e = groupState.expenses.find((x) => x.id === body.expenseId);
        if (!e) return { ok: false, reason: 'GONE' };
        const money = body.amountSubunits !== undefined || body.people !== undefined;
        if (money && body.baseSplitVersion !== e.splitVersion) return { ok: false, reason: 'STALE' };
        const before = snapshotOf(e);
        const snapshot = structuredClone(e);
        if (body.title) e.title = String(body.title);
        if (money) {
          e.amountSubunits = Number(body.amountSubunits ?? e.amountSubunits);
          e.payerUserId = String(body.payerUserId ?? e.payerUserId);
          e.splitMode = String(body.splitMode ?? e.splitMode);
          e.shares = sharesFor(e.splitMode, e.amountSubunits, peopleIds(body.people as never));
          e.splitVersion += 1;
        }
        const editId = `ge-${Date.now()}`;
        groupState.edits.unshift({ id: editId, expenseId: e.id, actorUserId: state.me ?? 'u-purity', before, after: snapshotOf(e), createdAt: Date.now(), undone: false, snapshot });
        return { ok: true, reason: null, changed: true, editId };
      }

      case 'group-expense-undo': {
        const edit = groupState.edits.find((x) => x.id === body.editId);
        if (!edit || edit.undone) return { ok: true, changed: false, reason: null };
        const i = groupState.expenses.findIndex((x) => x.id === edit.expenseId);
        groupState.expenses[i] = { ...edit.snapshot, splitVersion: groupState.expenses[i].splitVersion + 1 };
        edit.undone = true;
        return { ok: true, changed: true, reason: null };
      }

      case 'release':
        state.me = null;
        return { ok: true, released: true };

      default:
        fail('NOT_FOUND', 404);
    }
  });
}
