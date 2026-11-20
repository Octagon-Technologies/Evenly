// node --experimental-strip-types --test supabase/functions/extract-receipt/summary.test.ts
//
// The quick read fills a user's form, so a malformed field must arrive as null (the field stays empty)
// rather than as a wrong value the user then has to notice.
import { test } from "node:test";
import assert from "node:assert/strict";
import { isUsableSummary, normalizeSummary } from "./summary.ts";

test("a card transaction passes through cleaned", () => {
  const s = normalizeSummary({
    is_expense: true,
    source: "card_transaction",
    merchant_name: "  Uber ",
    expense_kind: "ride",
    category: "transport",
    printed_time: "7:42",
    total: "24.17",
    currency: "usd",
  });
  assert.deepEqual(s, {
    source: "card_transaction",
    merchant_name: "Uber",
    expense_kind: "ride",
    category: "transport",
    printed_time: "07:42",
    total_subunits: 2417,
    currency: "USD",
    short_title: null,
  });
});

test("the total keeps its cents and tolerates a stray symbol", () => {
  assert.equal(normalizeSummary({ is_expense: true, total: "$38.42" }).total_subunits, 3842);
  assert.equal(normalizeSummary({ is_expense: true, total: "1,204.50" }).total_subunits, 120450);
});

test("unknown enums and malformed fields become null, never a guess", () => {
  const s = normalizeSummary({
    is_expense: true,
    source: "fax",
    expense_kind: "casino",
    category: "gambling",
    printed_time: "8:05 PM",
    currency: "dollars",
  });
  assert.equal(s.source, "other");
  assert.equal(s.expense_kind, null);
  assert.equal(s.category, null);
  assert.equal(s.printed_time, null);
  assert.equal(s.currency, null);
  assert.equal(s.total_subunits, 0);
});

test("a reading with nothing in it is not usable", () => {
  assert.equal(isUsableSummary(normalizeSummary({ is_expense: true })), false);
  assert.equal(isUsableSummary(normalizeSummary({ is_expense: true, total: "5.00" })), true);
  assert.equal(isUsableSummary(normalizeSummary({ is_expense: true, merchant_name: "Netflix" })), true);
});
