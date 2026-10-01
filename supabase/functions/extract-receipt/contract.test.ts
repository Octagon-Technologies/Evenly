// node --experimental-strip-types --test supabase/functions/extract-receipt/contract.test.ts
//
// The header fields (merchant, venue kind, printed time) only feed the app's suggested bill name, so a
// bad value must be dropped to null rather than reach a user-visible title.
import { test } from "node:test";
import assert from "node:assert/strict";
import { normalizeReceipt, type RawReceipt } from "./contract.ts";

const base: RawReceipt = { currency: "USD", items: [], summary: [], is_receipt: true };

test("header fields pass through when well-formed", () => {
  const r = normalizeReceipt({ ...base, merchant_name: "  Tavolo   Trattoria ", venue_kind: "restaurant", printed_time: "8:05" });
  assert.equal(r.merchant_name, "Tavolo Trattoria");
  assert.equal(r.venue_kind, "restaurant");
  assert.equal(r.printed_time, "08:05");
});

test("missing or malformed header fields become null", () => {
  const r = normalizeReceipt({ ...base, merchant_name: "   ", venue_kind: "casino", printed_time: "8:05 PM" });
  assert.equal(r.merchant_name, null);
  assert.equal(r.venue_kind, null);
  assert.equal(r.printed_time, null);
  const none = normalizeReceipt(base);
  assert.equal(none.merchant_name, null);
  assert.equal(none.printed_time, null);
});

test("out-of-range times are dropped and long names capped", () => {
  assert.equal(normalizeReceipt({ ...base, printed_time: "24:10" }).printed_time, null);
  assert.equal(normalizeReceipt({ ...base, merchant_name: "x".repeat(80) }).merchant_name?.length, 40);
});
