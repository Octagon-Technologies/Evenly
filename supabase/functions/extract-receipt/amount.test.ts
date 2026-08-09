// Written against `node:test` (not `Deno.test`) so it runs with the toolchain that is actually on a dev
// machine here — Deno is not installed:
//
//   node --experimental-strip-types --test supabase/functions/extract-receipt/amount.test.ts
//
// Deno supports node:test too, so this stays runnable if the function ever gets a `deno test` step.
import { test } from "node:test";
import assert from "node:assert/strict";
import { parseAmountSubunits } from "./amount.ts";

// The Tom's Watch Bar receipt from the 2026-08-08 bug report, where every one of these came back from the
// model as its dollar part with two zeros appended (22.74 -> 2200) and the $103.50 bill landed as $94.00.
const REGRESSION_RECEIPT: Array<[string, number]> = [
  ["22.74", 2274],
  ["38.22", 3822],
  ["12.99", 1299],
  ["19.49", 1949],
  ["14.24", 1424],
  ["107.68", 10768],
  ["3.74", 374],
  ["97.18", 9718],
  ["6.32", 632],
  ["103.50", 10350],
];

test("keeps the cents of every amount on the receipt that regressed", () => {
  for (const [printed, want] of REGRESSION_RECEIPT) {
    assert.equal(parseAmountSubunits(printed), want, printed);
  }
});

test("that receipt's lines and extras reconcile against its printed total", () => {
  const lines = ["22.74", "38.22", "12.99", "19.49", "14.24"]
    .reduce((sum, p) => sum + parseAmountSubunits(p), 0);
  const computed = lines
    + parseAmountSubunits("6.32")    // tax
    + parseAmountSubunits("3.74")    // srv fee -> gratuity
    + parseAmountSubunits("0")       // tip
    - parseAmountSubunits("14.24");  // discount
  assert.equal(lines, 10768);
  assert.equal(computed, parseAmountSubunits("103.50"));
});

test("handles the shapes a model reaches for around the schema pattern", () => {
  const cases: Array<[unknown, number]> = [
    ["0", 0],
    ["22", 2200],
    ["22.0", 2200],
    ["22.7", 2270],
    ["$22.74", 2274],
    ["22.74 USD", 2274],
    [" 22.74 ", 2274],
    ["-14.24", 1424],   // discount is a positive magnitude
    [22.74, 2274],      // ignored the string type entirely
    [19.49, 1949],      // the float trap: 19.49 * 100 === 1948.9999999999998
    [22, 2200],
  ];
  for (const [input, want] of cases) {
    assert.equal(parseAmountSubunits(input), want, JSON.stringify(input));
  }
});

test("rounds a third decimal place into the cent rather than dropping it", () => {
  assert.equal(parseAmountSubunits("22.749"), 2275);
  assert.equal(parseAmountSubunits("3.451"), 345);
  assert.equal(parseAmountSubunits("0.999"), 100);
});

test("reads separators the way a receipt means them", () => {
  assert.equal(parseAmountSubunits("1,234.56"), 123456);
  assert.equal(parseAmountSubunits("1.234,56"), 123456);
  assert.equal(parseAmountSubunits("1,234"), 123400); // lone comma before 3 digits is grouping
  assert.equal(parseAmountSubunits("1.234"), 123);    // lone dot before 3 digits is a 3-decimal price
  assert.equal(parseAmountSubunits("12345"), 1234500);
});

test("unreadable input is 0, which the caller's gates treat as a failed read", () => {
  for (const input of ["", "n/a", null, undefined, {}, [], NaN]) {
    assert.equal(parseAmountSubunits(input), 0, JSON.stringify(input));
  }
});
