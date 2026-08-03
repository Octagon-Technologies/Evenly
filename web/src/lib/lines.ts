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

import type { BillResponse, PendingEdit } from './api.ts';
import type { BillItem, IndividualClaim, ItemStatus, SplitBillInput } from './money/index.ts';
import { toSplitInput } from './money/index.ts';

/** A pending ADD is folded into the bill under this id prefix, so it can never collide with a real one. */
const PENDING_PREFIX = 'pending:';

export function isPendingLine(itemId: string): boolean {
  return itemId.startsWith(PENDING_PREFIX);
}

function undecidedAdds(bill: BillResponse): PendingEdit[] {
  return bill.pendingEdits.filter((e) => e.kind === 'ADD' && e.decided_at === null);
}

/**
 * The engine's input, with each guest's own unapproved **addition** folded in as a real line she has
 * claimed (spec E19: "her total includes it … she must never see a number she cannot account for").
 *
 * Only ADD is folded. A pending reprice or requantity would move money that has not been agreed, and
 * the line it touches is annotated instead — the narrower reading of E19, and the one that cannot
 * show anyone a number the payer has not seen.
 */
export function splitInputWithPending(bill: BillResponse): SplitBillInput {
  const base = toSplitInput(bill);
  const adds = undecidedAdds(bill);
  if (adds.length === 0) return base;

  const items: BillItem[] = adds.map((e) => ({
    itemId: PENDING_PREFIX + e.id,
    lineTotalSubunits: (e.proposed_unit_price_subunits ?? 0) * (e.proposed_quantity ?? 1),
    quantity: e.proposed_quantity ?? 1,
  }));
  const claims: IndividualClaim[] = adds.map((e) => ({
    itemId: PENDING_PREFIX + e.id,
    userId: e.proposed_by,
    units: e.proposed_quantity ?? 1,
  }));

  return {
    ...base,
    items: [...base.items, ...items],
    individualClaims: [...(base.individualClaims ?? []), ...claims],
  };
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
  /** Set on a line that only exists as an unapproved proposal (spec E19). */
  pending: { proposedByName: string; mine: boolean } | null;
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

  const real: LineView[] = bill.items.map((item) => {
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
      pending: null,
    };
  });

  // Unapproved additions sit at the foot of the list, marked, and offer nothing: there is no row to
  // claim yet, and a second person joining a line that may be rejected is a promise we cannot keep.
  const pending: LineView[] = undecidedAdds(bill).map((edit) => {
    const itemId = PENDING_PREFIX + edit.id;
    const quantity = edit.proposed_quantity ?? 1;
    return {
      itemId,
      label: edit.proposed_label ?? 'Something',
      quantity,
      lineTotalSubunits: (edit.proposed_unit_price_subunits ?? 0) * quantity,
      chips: [
        {
          userId: edit.proposed_by,
          name: nameOf(edit.proposed_by),
          isMe: edit.proposed_by === myUserId,
          portionId: null,
        },
      ],
      myUnits: edit.proposed_by === myUserId ? quantity : 0,
      myPortionId: null,
      mine: edit.proposed_by === myUserId,
      assigned: quantity,
      left: 0,
      status: 'RESOLVED',
      action: 'none',
      myShareSubunits: (myUserId && perItemByUser[itemId]?.[myUserId]) || 0,
      pending: { proposedByName: nameOf(edit.proposed_by), mine: edit.proposed_by === myUserId },
    };
  });

  return [...real, ...pending];
}

/** The header's progress: claimed money against the bill, and people done against participants. */
export function claimProgress(lines: LineView[]): { claimedSubunits: number; totalSubunits: number } {
  let claimedSubunits = 0;
  let totalSubunits = 0;
  // Unapproved additions are excluded: the progress bar measures the bill as it stands, and the
  // payer has not agreed to a bigger one yet.
  for (const line of lines.filter((l) => l.pending === null)) {
    totalSubunits += line.lineTotalSubunits;
    if (line.quantity > 0) {
      claimedSubunits += Math.round((line.lineTotalSubunits * Math.min(line.assigned, line.quantity)) / line.quantity);
    } else if (line.assigned > 0) {
      claimedSubunits += line.lineTotalSubunits;
    }
  }
  return { claimedSubunits, totalSubunits };
}
