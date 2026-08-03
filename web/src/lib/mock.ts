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
 *   `?mock&state=expired` → the link died                              → frame 9
 *   `?mock&state=gone`    → revoked / deleted
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
};

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
  pendingEdits: [] as Array<Record<string, unknown>>,
  participants: ['u-andrew', 'u-mary', 'u-bob', 'u-steve', 'u-mo'],
  doneAt: null as number | null,
  me: null as string | null,
  seq: 0,
};

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
    expiresAt: Date.now() + 60 * 60 * 1000 * 40,
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
          },
          items: ITEMS,
          claims: state.claims,
          shares: state.shares,
          pendingEdits: state.pendingEdits,
          participants: state.participants.map((userId) => ({
            userId,
            name: NAMES[userId] ?? 'Someone',
            doneAt: userId === state.me ? state.doneAt : null,
          })),
          namesByUser: NAMES,
          payer: { userId: 'u-andrew', name: 'Andrew', app: 'venmo', handle: '@andrew-chelimo' },
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
        const id = nextId('pe');
        state.pendingEdits.push({
          id,
          item_id: body.itemId ?? null,
          kind: body.kind,
          proposed_label: body.label ?? null,
          proposed_quantity: body.quantity ?? null,
          proposed_unit_price_subunits: body.unitPriceSubunits ?? null,
          previous_label: null,
          previous_quantity: null,
          previous_unit_price_subunits: null,
          proposed_by: state.me,
          proposed_at: Date.now(),
          decided_at: null,
          decision: null,
        });
        return { ok: true, pendingEditId: id };
      }

      case 'done':
        state.doneAt = body.done ? Date.now() : null;
        return { ok: true };

      case 'release':
        state.me = null;
        return { ok: true, released: true };

      default:
        fail('NOT_FOUND', 404);
    }
  });
}
