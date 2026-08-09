// Run with:
//   node --experimental-strip-types --test supabase/functions/extract-receipt/billMath.test.ts
import { test } from "node:test";
import assert from "node:assert/strict";
import { normalizeReceipt, type RawReceipt } from "./contract.ts";
import { computeBill, describeDiscrepancy } from "./billMath.ts";

// The Tom's Watch Bar receipt from the 2026-08-08 report, printed exactly as it appears:
//
//   1 Bacon Avocado Burger        22.74     Pre-discount Subtotal   107.68
//   3 Strawberry Mint Sparkler    38.22     Discount Total         - 14.24
//   1 Pineapple Ginger Fizz       12.99     Srv Fee (4.00%)           3.74
//   1 Adobo Chicken Tacos         19.49     Subtotal                 97.18
//   1 Loaded Fries                14.24     Tax                       6.32
//     Wise Free App (100%)      - 14.24     Total                   103.50
//                                           Credit                -103.50
//                                           Amount Due                0.00
//
// Note what this receipt does that broke the old schema: it prints TWO subtotals (one before the discount
// and service fee, one after), so a field described as "the subtotal, which must equal the sum of the
// lines" names two different numbers. It also prints a 0.00 "Amount Due" that must never be read as the
// bill's total, and a Credit line equal to the total.
const TOMS: RawReceipt = {
  is_receipt: true,
  currency: "USD",
  items: [
    { label: "Bacon Avocado Burger", quantity: 1, line_total: "22.74" },
    { label: "Strawberry Mint Sparkler", quantity: 3, line_total: "38.22" },
    { label: "Pineapple Ginger Fizz", quantity: 1, line_total: "12.99" },
    { label: "Adobo Chicken Tacos", quantity: 1, line_total: "19.49" },
    { label: "Loaded Fries", quantity: 1, line_total: "14.24" },
  ],
  summary: [
    { label: "Wise Free App (100%)", kind: "discount", amount: "14.24" },
    { label: "Pre-discount Subtotal", kind: "subtotal", amount: "107.68" },
    { label: "Srv Fee (4.00%)", kind: "gratuity", amount: "3.74" },
    { label: "Subtotal", kind: "subtotal", amount: "97.18" },
    { label: "Tax", kind: "tax", amount: "6.32" },
    { label: "Total", kind: "total", amount: "103.50" },
    { label: "Credit", kind: "payment", amount: "103.50" },
    { label: "Amount Due", kind: "payment", amount: "0.00" },
  ],
};

test("the receipt that regressed now reconciles to the cent", () => {
  const math = computeBill(normalizeReceipt(TOMS));
  assert.equal(math.itemSum, 10768);
  assert.equal(math.extras.tax_subunits, 632);
  assert.equal(math.extras.gratuity_subunits, 374);
  assert.equal(math.extras.discount_subunits, 1424);
  assert.equal(math.computedTotal, 10350);
  assert.equal(math.printedTotal, 10350);
  assert.equal(math.residual, 0);
  assert.equal(math.reconciles, true);
});

test("both printed subtotals are kept, and neither is allowed to validate anything", () => {
  const math = computeBill(normalizeReceipt(TOMS));
  assert.deepEqual(math.printedSubtotals, [10768, 9718]);
  // The old gate passed on `Σ lines ≈ subtotal`. Here the lines equal ONE of the two printed subtotals,
  // so that gate would still fire — which is the point: reconciliation must not consult a subtotal at all.
  const bogus = computeBill(normalizeReceipt({
    ...TOMS,
    summary: TOMS.summary.filter((l) => l.kind !== "total"),
  }));
  assert.equal(bogus.printedTotal, 0);
  assert.equal(bogus.reconciles, false, "no printed total means nothing to check against, not a pass");
});

test("the exact cents-truncation draft is now caught instead of passing as verified", () => {
  // What the model actually returned on 2026-08-08, expressed in the new schema.
  const truncated = computeBill(normalizeReceipt({
    ...TOMS,
    items: TOMS.items.map((it) => ({ ...it, line_total: it.line_total.split(".")[0] })),
    summary: [
      { label: "Wise Free App (100%)", kind: "discount", amount: "14" },
      { label: "Srv Fee (4.00%)", kind: "gratuity", amount: "3" },
      { label: "Tax", kind: "tax", amount: "0" },
      { label: "Total", kind: "total", amount: "103.50" },
    ],
  }));
  assert.equal(truncated.computedTotal, 9400);
  assert.equal(truncated.printedTotal, 10350);
  assert.equal(truncated.residual, -950);
  assert.equal(truncated.reconciles, false);
});

test("a 0.00 'Amount Due' can never be mistaken for the bill's total", () => {
  const paidOff = computeBill(normalizeReceipt({
    ...TOMS,
    summary: TOMS.summary.map((l) => (l.label === "Amount Due" ? { ...l, kind: "total" } : l)),
  }));
  // Two `total` lines now: 103.50 and 0.00. The larger wins, so a settled bill is not a free one.
  assert.equal(paidOff.printedTotal, 10350);
  assert.equal(paidOff.reconciles, true);
});

test("a charge with no slot in the app's bill still has to be accounted for", () => {
  const delivery = computeBill(normalizeReceipt({
    is_receipt: true,
    currency: "USD",
    items: [{ label: "Large pepperoni", quantity: 1, line_total: "18.00" }],
    summary: [
      { label: "Delivery fee", kind: "other", amount: "4.99" },
      { label: "Tax", kind: "tax", amount: "1.50" },
      { label: "Total", kind: "total", amount: "24.49" },
    ],
  }));
  assert.equal(delivery.extras.other_subunits, 499);
  assert.equal(delivery.computedTotal, 2449);
  // Without `other` in the sum this reconciles only by ignoring a real $4.99 charge.
  assert.equal(delivery.reconciles, true);
});

test("the comp printed among the items is not double-counted with its Discount Total", () => {
  // Tom's prints the SAME discount twice: "Wise Free App (100%) - 14.24" inline under the dish it comps,
  // and "Discount Total - 14.24" in the totals block. `Pre-discount Subtotal 107.68` equals the five items
  // exactly, which proves the inline line is a preview of the discount and not a sixth item.
  //
  // Recorded once (correct): the bill reconciles.
  assert.equal(computeBill(normalizeReceipt(TOMS)).residual, 0);

  // Recorded twice: $14.24 comes off the bill that was only discounted once.
  const doubled = computeBill(normalizeReceipt({
    ...TOMS,
    summary: [
      { label: "Wise Free App (100%)", kind: "discount", amount: "14.24" },
      ...TOMS.summary,
    ],
  }));
  assert.equal(doubled.extras.discount_subunits, 2848);
  assert.equal(doubled.computedTotal, 8926);
  assert.equal(doubled.residual, -1424);
  assert.equal(doubled.reconciles, false, "a double-counted comp must reach the verify turn, not the user");
});

test("a negative line among the items is never recorded as an item", () => {
  // The schema's amount pattern has no sign, so a comp cannot be expressed as an item at all — the model
  // has to route it to `summary`. Were it ever to land in items, the item sum would no longer match the
  // printed pre-discount subtotal, which is the signal this pins.
  const items = normalizeReceipt(TOMS).items;
  assert.equal(items.length, 5);
  assert.ok(items.every((i) => i.line_total_subunits > 0));
  assert.equal(items.reduce((s, i) => s + i.line_total_subunits, 0), 10768); // = printed Pre-discount Subtotal
});

test("a blank read does not reconcile via 0 = 0", () => {
  const blank = computeBill(normalizeReceipt({
    is_receipt: true, currency: "USD", items: [], summary: [],
  }));
  assert.equal(blank.computedTotal, 0);
  assert.equal(blank.printedTotal, 0);
  assert.equal(blank.reconciles, false);
});

test("the verify message shows the working and never names the missing amount", () => {
  const receipt = normalizeReceipt({
    ...TOMS,
    items: TOMS.items.map((it) => ({ ...it, line_total: it.line_total.split(".")[0] })),
    summary: [
      { label: "Wise Free App (100%)", kind: "discount", amount: "14" },
      { label: "Srv Fee (4.00%)", kind: "gratuity", amount: "3" },
      { label: "Tax", kind: "tax", amount: "0" },
      { label: "Total", kind: "total", amount: "103.50" },
    ],
  });
  const msg = describeDiscrepancy(receipt, computeBill(receipt));
  assert.match(msg, /Bacon Avocado Burger/);
  assert.match(msg, /sum of the 5 item lines/);
  assert.match(msg, /103\.50/);          // the printed total it must reconcile against
  assert.match(msg, /off by 9\.50/);     // the size of the gap
  assert.match(msg, /I did the arithmetic, not you/);
  assert.match(msg, /do NOT put the difference somewhere/);
});
