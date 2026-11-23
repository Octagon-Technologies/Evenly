// The record_expense contract: the "quick read" used by "Split one amount". One cheap pass that says what an
// expense was (who was paid, what kind of spend, the total) from either a printed receipt or a screenshot of
// a bank / card / payment-app transaction. It never lists items and never runs the verify or escalation
// tiers, because nothing here is split by line: the user sees one amount in an editable box.
//
// Same governing rule as contract.ts: THE MODEL DOES NO ARITHMETIC. `total` is the digits as printed and
// `parseAmountSubunits` converts them. Cleaning "SQ *BLUE BOTTLE" into "Blue Bottle" is reading, and picking
// a category is classification; neither is a computed number.
//
// Pure module (no Deno.*, no network) so `summary.test.ts` and the receipt lab can import it under Node.

import { parseAmountSubunits } from "./amount.ts";
import { normalizeDate, normalizeMerchant, normalizeTime } from "./contract.ts";

export const EXPENSE_KINDS = [
  "restaurant", "cafe", "bar", "grocery", "shop", "ride", "flight", "transit", "fuel", "parking",
  "lodging", "entertainment", "subscription", "utility", "health", "other",
] as const;
export type ExpenseKind = (typeof EXPENSE_KINDS)[number];

export const EXPENSE_SOURCES = ["receipt", "card_transaction", "invoice", "payment_app", "other"] as const;
export type ExpenseSource = (typeof EXPENSE_SOURCES)[number];

/** Keys of the app's built-in categories (`CategoryDefaults` in the app). Never a group's custom category. */
export const CATEGORY_KEYS = [
  "food", "groceries", "transport", "lodging", "entertainment", "shopping", "utilities", "health", "other",
] as const;
export type CategoryKey = (typeof CATEGORY_KEYS)[number];

/** What the model emits. */
export interface RawExpenseSummary {
  is_expense: boolean;
  source?: string;
  merchant_name?: string;
  expense_kind?: string;
  category?: string;
  printed_time?: string;
  printed_date?: string;
  total?: unknown;
  currency?: string;
  short_title?: string;
}

/** The wire shape the app receives. Every field but `total_subunits` may be null. */
export interface ExpenseSummary {
  source: ExpenseSource;
  merchant_name: string | null;
  expense_kind: ExpenseKind | null;
  category: CategoryKey | null;
  printed_time: string | null;
  /** "YYYY-MM-DD" as shown, or null. */
  printed_date: string | null;
  /** 0 when no total could be read; the app then leaves its amount box alone. */
  total_subunits: number;
  currency: string | null;
  short_title: string | null;
}

const AMOUNT_FIELD = { type: "string", pattern: "^[0-9]+([.][0-9]{1,3})?$" } as const;

export const SUMMARY_TOOL = {
  name: "record_expense",
  description: "Record what this expense was, read off a receipt or a transaction screenshot. Performs no calculation.",
  input_schema: {
    type: "object",
    properties: {
      is_expense: {
        type: "boolean",
        description:
          "true if this shows money that was spent: a printed receipt, an invoice, or a screenshot of a " +
          "bank, card or payment-app transaction. false for anything else (a person, an unrelated photo or " +
          "screenshot). If false, leave every other field empty.",
      },
      source: {
        type: "string",
        enum: [...EXPENSE_SOURCES],
        description:
          "'receipt' a printed or emailed store receipt. 'card_transaction' a bank or card app's transaction " +
          "detail or statement line. 'invoice' a bill or invoice. 'payment_app' a Venmo/PayPal/Cash App style " +
          "payment. 'other' anything else.",
      },
      merchant_name: {
        type: "string",
        description:
          "Who was paid, as a person would say it. Clean up card descriptors: \"SQ *BLUE BOTTLE COFFEE\" is " +
          "\"Blue Bottle Coffee\", \"UBER *TRIP HELP.UBER.COM\" is \"Uber\", \"AMZN Mktp US*2K4\" is \"Amazon\". " +
          "No address, store number, or reference code. Empty if no payee is shown.",
      },
      expense_kind: {
        type: "string",
        enum: [...EXPENSE_KINDS],
        description:
          "What kind of spend this was. 'ride' a taxi or ride-hail trip. 'transit' a train, bus or metro fare. " +
          "'subscription' a recurring service (streaming, software, gym). 'utility' electricity, water, gas, " +
          "internet or phone. 'other' when none fits.",
      },
      category: {
        type: "string",
        enum: [...CATEGORY_KEYS],
        description:
          "The best expense category. 'food' restaurants, cafes, bars, takeout. 'groceries' supermarkets. " +
          "'transport' rides, fuel, parking, flights, transit. 'lodging' hotels and rentals. 'entertainment' " +
          "events, streaming, games. 'shopping' retail. 'utilities' household bills. 'health' pharmacy, " +
          "doctor, gym. 'other' when none fits.",
      },
      printed_time: {
        type: "string",
        description:
          "The time of the purchase or transaction if shown, as 24-hour HH:MM (a printed \"8:05 PM\" is \"20:05\"). " +
          "Empty if no time is shown.",
      },
      printed_date: {
        type: "string",
        description:
          "The date of the transaction as printed, as YYYY-MM-DD. When day and month could be either way round " +
          "(04/03/26), read it the way the receipt's country writes dates: day first everywhere except the US. " +
          "A two-digit year is 20xx. Leave empty if no date is printed. Never guess a date that is not printed.",
      },
      total: {
        ...AMOUNT_FIELD,
        description:
          "The amount actually paid: the grand total on a receipt, or the transaction amount on a bank or " +
          "payment screenshot (not a balance, not a credit limit). Copy the digits EXACTLY as shown, cents " +
          "included, e.g. \"38.42\". Digits and one decimal point only, no symbol or sign.",
      },
      currency: { type: "string", description: "ISO 4217 code, e.g. USD, EUR, KES. Best guess from symbols or locale." },
      short_title: {
        type: "string",
        description:
          "A 2 to 5 word name for this expense as a friend would log it, e.g. \"Netflix subscription\", " +
          "\"Electricity bill\", \"Concert tickets\". Plain words, no prices, no dates.",
      },
    },
    required: ["is_expense"],
  },
} as const;

export const SUMMARY_PROMPT =
  "Read what this expense was and record it with the record_expense tool. It may be a store receipt or a " +
  "screenshot of a bank, card or payment-app transaction. Copy the total digit for digit as shown; do not " +
  "add anything up or work anything out. If several amounts are shown, the total is the one that was " +
  "charged or paid. If this is not an expense at all, set is_expense to false and nothing else.";

const KIND_SET: ReadonlySet<string> = new Set(EXPENSE_KINDS);
const SOURCE_SET: ReadonlySet<string> = new Set(EXPENSE_SOURCES);
const CATEGORY_SET: ReadonlySet<string> = new Set(CATEGORY_KEYS);

/** The model's reading -> the wire shape. Drops anything malformed to null rather than half-trusting it. */
export function normalizeSummary(raw: RawExpenseSummary): ExpenseSummary {
  const currency = typeof raw?.currency === "string" && /^[A-Za-z]{3}$/.test(raw.currency.trim())
    ? raw.currency.trim().toUpperCase()
    : null;
  return {
    source: (SOURCE_SET.has(raw?.source ?? "") ? raw!.source : "other") as ExpenseSource,
    merchant_name: normalizeMerchant(raw?.merchant_name),
    expense_kind: KIND_SET.has(raw?.expense_kind ?? "") ? (raw!.expense_kind as ExpenseKind) : null,
    category: CATEGORY_SET.has(raw?.category ?? "") ? (raw!.category as CategoryKey) : null,
    printed_time: normalizeTime(raw?.printed_time),
    printed_date: normalizeDate(raw?.printed_date),
    total_subunits: parseAmountSubunits(raw?.total),
    currency,
    short_title: normalizeMerchant(raw?.short_title),
  };
}

/** A quick read is worth showing only if it says something: a payee, a kind of spend, or a total. */
export function isUsableSummary(s: ExpenseSummary): boolean {
  return s.merchant_name !== null || s.expense_kind !== null || s.total_subunits > 0 || s.short_title !== null;
}
