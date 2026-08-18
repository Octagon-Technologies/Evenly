/**
 * The TS half of the money CI gate (WEB_CLAIM_SPEC.md §7).
 *
 * Runs `test-vectors/bill-split.json` — the same file `BillSplitVectorsTest.kt` runs — against the
 * hand-ported engine. Kotlin is the authority: if this goes red, the port drifted, and the fix is
 * here unless the Kotlin rule itself deliberately changed.
 *
 *   node --experimental-strip-types --test test/vectors.test.ts     (or: npm test)
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import {
  allocate,
  splitBill,
  toSplitInput,
  type SplitBillInput,
  type Weight,
} from '../src/lib/money/index.ts';

interface AllocateCase {
  name: string;
  totalSubunits: number;
  weights: Array<{ userId: string; weight: number }>;
  expect: Record<string, number>;
}

interface SplitCase extends SplitBillInput {
  name: string;
  expect: Record<string, unknown>;
}

const vectors = JSON.parse(
  readFileSync(new URL('../../test-vectors/bill-split.json', import.meta.url), 'utf8'),
) as {
  allocate: AllocateCase[];
  splitBill: SplitCase[];
};

/** Every case must pin something; an un-recorded case is a hole in the gate, not a pass. */
function requireExpectations(group: string, name: string, expected: Record<string, unknown>): void {
  assert.ok(
    expected && Object.keys(expected).length > 0,
    `${group}/${name} has an empty \`expect\` — record it from Kotlin (see test-vectors/README.md)`,
  );
}

test('allocate vectors', async (t) => {
  for (const c of vectors.allocate) {
    await t.test(c.name, () => {
      requireExpectations('allocate', c.name, c.expect);
      const weights: Weight[] = c.weights.map((w) => [w.userId, w.weight] as const);
      assert.deepStrictEqual(allocate(c.totalSubunits, weights), c.expect);
    });
  }
});

test('splitBill vectors', async (t) => {
  for (const c of vectors.splitBill) {
    await t.test(c.name, () => {
      requireExpectations('splitBill', c.name, c.expect);
      const r = splitBill(c);
      const actual: Record<string, unknown> = {
        owedByUser: r.owedByUser,
        breakdownByUser: r.breakdownByUser,
        perItemByUser: r.perItemByUser,
        items: r.items.map((i) => ({
          itemId: i.itemId,
          quantity: i.quantity,
          individualUnits: i.individualUnits,
          hasSharers: i.hasSharers,
          status: i.status,
        })),
      };
      // A case may pin a subset, exactly as the Kotlin runner allows.
      for (const key of Object.keys(c.expect)) {
        assert.deepStrictEqual(actual[key], c.expect[key], `splitBill/${c.name} → expect.${key}`);
      }
    });
  }
});

/**
 * Invariants the vectors alone can't state, checked across every splitBill case: the parts of a tab
 * always reconcile to the tab, and per-line allocations always sum to each person's item subtotal.
 * A port that got the parts right but assembled them wrong would still pass the recorded numbers.
 */
test('splitBill invariants hold for every vector', () => {
  for (const c of vectors.splitBill) {
    const r = splitBill(c);
    for (const [userId, owed] of Object.entries(r.owedByUser)) {
      const b = r.breakdownByUser[userId];
      assert.ok(b, `${c.name}: ${userId} has a tab but no breakdown`);
      assert.equal(
        b.itemsSubunits + b.taxSubunits + b.tipSubunits - b.discountSubunits,
        owed,
        `${c.name}: ${userId}'s parts do not sum to their tab`,
      );
      const fromLines = Object.values(r.perItemByUser).reduce((sum, byUser) => sum + (byUser[userId] ?? 0), 0);
      assert.equal(fromLines, b.itemsSubunits, `${c.name}: ${userId}'s per-line split ≠ their item subtotal`);
    }
  }
});

/**
 * The two money invariants, mirroring `BillSplitVectorsTest.assertMoneyInvariants`. A recorded
 * expectation only pins the case someone thought to write down; these catch the variants nobody
 * enumerated, which is exactly how findings #20 and #21 survived their own unit tests.
 *
 * Both are gated on the bill being fully claimed: mid-claim the unclaimed remainder deliberately
 * rides a phantom bucket, and an over-claimed line deliberately over-sums.
 */
test('a fully-claimed bill always has a positive total its shares sum to', () => {
  for (const c of vectors.splitBill) {
    const r = splitBill(c);
    if (r.items.length === 0 || !r.items.every((i) => i.status === 'RESOLVED')) continue;

    // Normalised exactly as the engine normalises: a line total is never negative, and a discount never
    // exceeds the item subtotal it rides proportional to. A bill breaking either is invalid input the
    // engine neutralises (findings D1, D4), so the total these invariants are stated against is the
    // normalised one, not the raw arithmetic.
    const e = c.extras;
    const lineTotals = c.items.reduce((s, i) => s + Math.max(i.lineTotalSubunits, 0), 0);
    const total =
      lineTotals +
      (e?.taxSubunits ?? 0) +
      (e?.gratuitySubunits ?? 0) +
      (e?.otherChargesSubunits ?? 0) +
      (e?.tipSubunits ?? 0) -
      Math.min(Math.max(e?.discountSubunits ?? 0, 0), lineTotals);

    // An all-free bill (every line 0, no extras) is a legitimate 0 and pins the divide-by-zero path.
    if (total === 0 && lineTotals === 0) continue;

    assert.ok(total > 0, `${c.name}: a bill's total must be positive, was ${total} (#21)`);
    const owed = Object.values(r.owedByUser).reduce((s, v) => s + v, 0);
    assert.equal(owed, total, `${c.name}: a fully-claimed bill's shares must sum to its total (#20)`);
  }
});

/**
 * Portions consume a line's unit costs in list order, and those costs differ by a subunit on an
 * indivisible line — so the list order used to decide who paid the odd cent. The rows arrive in
 * whatever order the query returned them, which differs between the device that authored them and one
 * that pulled them, so two devices derived different money for the same bill (finding D3). A vector
 * can only pin ONE order; this feeds every order.
 */
test('shared portions produce the same money in any row order', () => {
  const items = [{ itemId: 'dosa', lineTotalSubunits: 1000, quantity: 3 }]; // 334 / 333 / 333
  const portions = [
    { itemId: 'dosa', portionId: 'p1', quantity: 1, members: ['a'] },
    { itemId: 'dosa', portionId: 'p2', quantity: 1, members: ['b'] },
    { itemId: 'dosa', portionId: 'p3', quantity: 1, members: ['c'] },
  ];
  const permutations = <T,>(xs: readonly T[]): T[][] =>
    xs.length <= 1
      ? [[...xs]]
      : xs.flatMap((x, i) => permutations(xs.filter((_, j) => j !== i)).map((rest) => [x, ...rest]));

  for (const order of permutations(portions)) {
    const r = splitBill({ items, sharedPortions: order });
    assert.deepStrictEqual(
      r.owedByUser,
      { a: 334, b: 333, c: 333 },
      `portion order ${order.map((p) => p.portionId).join(',')} changed the money`,
    );
  }
});

/**
 * The wire shape → engine adapter (`fromApi.ts`), mirroring the Kotlin adapters in
 * `BillMaterializer.kt`: a null `portion_id` is a legacy all-leftover member, portioned rows group by
 * portion, and the group's denormalised quantity comes from the first row.
 */
test('toSplitInput maps the /web-claim/bill payload', () => {
  const input = toSplitInput({
    expense: {
      id: 'e1',
      taxSubunits: 100,
      gratuitySubunits: 0,
      tipSubunits: 200,
      tipSplitMode: 'EVEN',
      discountSubunits: 0,
    },
    items: [
      { id: 'i1', label: 'Nachos', quantity: 4, line_total_subunits: 1600 },
      { id: 'i2', label: 'Naan', quantity: 1, line_total_subunits: 300 },
    ],
    claims: [{ item_id: 'i1', user_id: 'u1', quantity: 2 }],
    shares: [
      { item_id: 'i1', user_id: 'u2', portion_id: 'p1', quantity: 1 },
      { item_id: 'i1', user_id: 'u3', portion_id: 'p1', quantity: 1 },
      { item_id: 'i2', user_id: 'u4', portion_id: null, quantity: 1 },
    ],
    participants: [{ userId: 'u1' }, { userId: 'u2' }, { userId: 'u3' }, { userId: 'u4' }],
  });

  assert.deepStrictEqual(input.items, [
    { itemId: 'i1', lineTotalSubunits: 1600, quantity: 4 },
    { itemId: 'i2', lineTotalSubunits: 300, quantity: 1 },
  ]);
  assert.deepStrictEqual(input.individualClaims, [{ itemId: 'i1', userId: 'u1', units: 2 }]);
  assert.deepStrictEqual(input.sharedPortions, [
    { itemId: 'i1', portionId: 'p1', quantity: 1, members: ['u2', 'u3'] },
  ]);
  assert.deepStrictEqual(input.sharedMembers, [{ itemId: 'i2', userId: 'u4' }]);
  assert.deepStrictEqual(input.participants, ['u1', 'u2', 'u3', 'u4']);
  assert.equal(input.extras?.tipSplitMode, 'EVEN');

  // u1 has 2 of 4 nachos ($8), {u2,u3} split a third ($2 each), one is still unclaimed; u4 has the naan.
  const r = splitBill(input);
  assert.deepStrictEqual(r.perItemByUser['i1'], { u1: 800, u2: 200, u3: 200 });
  assert.deepStrictEqual(r.perItemByUser['i2'], { u4: 300 });
  assert.equal(r.items[0].status, 'UNCLAIMED');
  assert.equal(r.items[1].status, 'RESOLVED');
});
