/**
 * Turns the bill's raw rows into what the claim list draws: for each line, who is on it and what it
 * offers *you*. Pure, so the rules in spec §2.5 and §2.6 are testable without a browser.
 *
 * The two rules that must survive every future edit of this file:
 *
 * - **Every line is always visible, whatever its state** (§2.5). A claimed line changes what it
 *   offers, never whether it exists. Filter this list to "what's left" and Jane can never join the
 *   chicken she shared with Mary, and the split is silently wrong forever.
 * - **The affordance is a chip in the people row** (§2.6), never a button in the price column. That
 *   is also why `＋ Add me` never has to say the word "shared": the row shows Mary, you add yourself,
 *   and sharing is the inference.
 */

import type { BillChange, BillResponse } from './api.ts';
import type { ItemStatus } from './money/index.ts';

/**
 * One entry in the bill's change log, ready to render (spec §2.7, §3.9.1's web mirror).
 *
 * There is deliberately no "fold this into the split" step any more. An edit **applies on write**, so
 * by the time the rows arrive the money already includes it; folding it a second time would double it.
 * That is also the honest reading of E19: a guest's total includes her edit because her edit is real.
 */
export interface ChangeView {
  id: string;
  kind: BillChange['kind'];
  /** "Purity added Mango sticky rice". */
  byName: string;
  mine: boolean;
  label: string;
  /** Signed effect on the bill total, for the "it took the bill to X" line. */
  deltaSubunits: number;
  /** `false` once someone has undone it; the row stays on screen as history. */
  live: boolean;
  /** Who undid it, already resolved to "you" when that was this guest. */
  undoneByName: string | null;
}

function lineTotalOf(quantity: number | null, perUnit: number | null): number {
  return (perUnit ?? 0) * (quantity ?? 1);
}

/** The log, oldest first, with the arithmetic already done. Pure, so the copy is testable. */
export function buildChanges(bill: BillResponse, myUserId: string | null): ChangeView[] {
  const nameOf = (userId: string | null): string =>
    userId && userId === myUserId ? 'you' : (userId && bill.namesByUser[userId]) || 'someone';

  return bill.pendingEdits.map((e) => {
    const previous = lineTotalOf(e.previous_quantity, e.previous_unit_price_subunits);
    const proposed =
      e.kind === 'REMOVE'
        ? 0
        : e.kind === 'RELABEL'
          ? previous
          : lineTotalOf(e.proposed_quantity ?? e.previous_quantity, e.proposed_unit_price_subunits ?? e.previous_unit_price_subunits);
    return {
      id: e.id,
      kind: e.kind,
      // Capitalised at the start of "You added …"; `undoneByName` sits mid-sentence and stays lower.
      byName: nameOf(e.proposed_by),
      mine: e.proposed_by === myUserId,
      label: e.proposed_label ?? e.previous_label ?? 'an item',
      deltaSubunits: proposed - (e.kind === 'ADD' ? 0 : previous),
      live: e.decision === 'APPLIED',
      undoneByName: e.decision === 'UNDONE' ? nameOf(e.decided_by) : null,
    };
  });
}

export interface LineChip {
  userId: string;
  name: string;
  isMe: boolean;
  /** The portion this chip belongs to, or null for a solo claim / legacy all-leftover share. */
  portionId: string | null;
}

/** What the trailing chip in the people row offers (spec §2.6). */
export type LineAction = 'claim' | 'add-me' | 'none';

export interface LineView {
  itemId: string;
  label: string;
  quantity: number;
  lineTotalSubunits: number;
  chips: LineChip[];
  /** Units I hold as a solo claim on this line. */
  myUnits: number;
  /** The portion I am in, if any — a guest is in at most one shared slice per line. */
  myPortionId: string | null;
  mine: boolean;
  assigned: number;
  left: number;
  status: ItemStatus;
  action: LineAction;
  /** What I owe for this line right now, straight from the money engine. */
  myShareSubunits: number;
}

export interface BuildLinesInput {
  bill: BillResponse;
  myUserId: string | null;
  /** `perItemByUser` from `splitBill` — the penny-exact per-line split, never recomputed here. */
  perItemByUser: Record<string, Record<string, number>>;
  itemStatus: Record<string, ItemStatus>;
}

export function buildLines({ bill, myUserId, perItemByUser, itemStatus }: BuildLinesInput): LineView[] {
  const nameOf = (userId: string): string => bill.namesByUser[userId] ?? 'Someone';

  return bill.items.map((item) => {
    const claims = bill.claims.filter((c) => c.item_id === item.id && c.quantity > 0);
    const shares = bill.shares.filter((s) => s.item_id === item.id);

    // Portions carry a denormalised quantity across their rows, so the first row's is the slice's.
    const portionQuantities = new Map<string, number>();
    for (const s of shares) {
      if (s.portion_id !== null && !portionQuantities.has(s.portion_id)) {
        portionQuantities.set(s.portion_id, s.quantity);
      }
    }
    const legacyShares = shares.filter((s) => s.portion_id === null);
    const soloUnits = claims.reduce((sum, c) => sum + c.quantity, 0);
    const portionUnits = [...portionQuantities.values()].reduce((sum, q) => sum + q, 0);

    // Mirrors the engine: explicit portions define a line's sharing; the legacy null-portion set only
    // applies when there are none, and then it absorbs everything left after the solo claims.
    const assigned =
      portionQuantities.size > 0
        ? soloUnits + portionUnits
        : legacyShares.length > 0
          ? item.quantity
          : soloUnits;

    const chips: LineChip[] = [
      ...claims.map((c) => ({
        userId: c.user_id,
        name: nameOf(c.user_id),
        isMe: c.user_id === myUserId,
        portionId: null,
      })),
      ...shares.map((s) => ({
        userId: s.user_id,
        name: nameOf(s.user_id),
        isMe: s.user_id === myUserId,
        portionId: s.portion_id,
      })),
    ];

    const myClaim = myUserId ? claims.find((c) => c.user_id === myUserId) : undefined;
    const myShare = myUserId ? shares.find((s) => s.user_id === myUserId) : undefined;
    const mine = Boolean(myClaim || myShare);
    const left = Math.max(item.quantity - assigned, 0);

    return {
      itemId: item.id,
      label: item.label,
      quantity: item.quantity,
      lineTotalSubunits: item.line_total_subunits,
      chips,
      myUnits: myClaim?.quantity ?? 0,
      myPortionId: myShare?.portion_id ?? null,
      mine,
      assigned,
      left,
      status: itemStatus[item.id] ?? 'UNCLAIMED',
      // On it already → nothing to offer. Units left → claim one. None left but someone is on it →
      // join them. An empty line always has units left, so it lands on `claim` too.
      action: mine ? 'none' : left > 0 ? 'claim' : 'add-me',
      myShareSubunits: (myUserId && perItemByUser[item.id]?.[myUserId]) || 0,
    };
  });
}

/** The header's progress: claimed money against the bill, and people done against participants. */
export function claimProgress(lines: LineView[]): { claimedSubunits: number; totalSubunits: number } {
  let claimedSubunits = 0;
  let totalSubunits = 0;
  // Every line here is a real line. A guest's addition is one of them from the moment she adds it, so
  // there is nothing to exclude and the bar measures the bill as it actually stands.
  for (const line of lines) {
    totalSubunits += line.lineTotalSubunits;
    if (line.quantity > 0) {
      claimedSubunits += Math.round((line.lineTotalSubunits * Math.min(line.assigned, line.quantity)) / line.quantity);
    } else if (line.assigned > 0) {
      claimedSubunits += line.lineTotalSubunits;
    }
  }
  return { claimedSubunits, totalSubunits };
}
