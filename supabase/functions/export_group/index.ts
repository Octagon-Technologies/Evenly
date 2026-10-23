// Evenly group export. Returns a group's whole ledger as a single
// CSV: every expense, who paid, what each person's share was, and every settlement.
//
// The directory for this function existed empty for months and `supabase/AGENTS.md` listed it as live.
// It was not: nothing was ever written or deployed. This is the first implementation.
//
//   POST { "groupId": "..." }  ->  200 text/csv
//
// Auth: the signed-in user's access token as the Bearer, anon key in `apikey` (same shape as
// extract-receipt). The caller must be an ACTIVE member. Export is part of the free product.
//
// ── The CSV shape ────────────────────────────────────────────────────────────────────────────────
// One row per expense and per settlement, with ONE COLUMN PER MEMBER holding that person's net for the
// row: what they paid minus what they owed. A $60 dinner split three ways reads +40 for the payer and
// -20 for the other two, so every row sums to zero and each column sums to that person's balance. This
// is the format a spreadsheet user can actually audit, rather than a dump of internal tables.
//
// The final "Unassigned" column is what makes "every row sums to zero" actually true. An itemized bill
// with lines nobody has claimed yet has shares totalling LESS than the bill, and without this column
// such a row silently fails to balance and the file looks broken to the one person most likely to check
// it. Money nobody has claimed is a real, nameable state, so it gets a column instead of a discrepancy.
//
// The totals row is emitted ONLY when every row shares one currency. Summing across currencies would
// produce a confident wrong number in a money app, and an absent total the file explains is strictly
// better than a total nobody can trust. There is no FX conversion here by design: the rates that would
// make one possible live in the app, and silently applying today's rate to a year-old dinner is its own
// kind of lie.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

interface ExportRequest {
  groupId?: string;
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** RFC 4180: quote when the value holds a comma, quote or newline, and double any inner quotes. */
function csvCell(value: string): string {
  if (!/[",\n\r]/.test(value)) return value;
  return `"${value.replace(/"/g, '""')}"`;
}

function csvRow(cells: string[]): string {
  return cells.map(csvCell).join(",");
}

/** Integer minor units to a plain decimal string. Never floating point: 0.1 + 0.2 has no place in a
 *  file someone will reconcile against their bank. */
function money(subunits: number): string {
  const neg = subunits < 0;
  const abs = Math.abs(subunits);
  const whole = Math.floor(abs / 100);
  const cents = abs % 100;
  return `${neg ? "-" : ""}${whole}.${cents.toString().padStart(2, "0")}`;
}

/** Epoch millis to YYYY-MM-DD in UTC. Stable and sortable, which a localized date is not.
 *  `expenses.expense_date` is ALREADY a "2026-06-16" text column and must not come through here;
 *  `settlements.settled_at` is epoch millis and must. */
function isoDate(epochMillis: number): string {
  return new Date(epochMillis).toISOString().slice(0, 10);
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);

  let payload: ExportRequest;
  try {
    payload = await req.json();
  } catch {
    return json({ error: "invalid JSON body" }, 400);
  }
  const groupId = payload.groupId;
  if (!groupId) return json({ error: "groupId required" }, 400);

  const authHeader = req.headers.get("Authorization") ?? "";
  const callerClient = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    { global: { headers: { Authorization: authHeader } } },
  );
  const { data: userData } = await callerClient.auth.getUser();
  const callerId = userData?.user?.id;
  if (!callerId) return json({ error: "unauthorized" }, 401);

  // Service role for everything below: RLS is membership-blind today, and the export must read rows
  // belonging to every member of the group, not just the caller's. The membership check immediately
  // below is what authorises that, and it is the only thing that does.
  const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

  const { data: membership, error: memberError } = await db
    .from("members")
    .select("id")
    .eq("group_id", groupId)
    .eq("user_id", callerId)
    .eq("status", "ACTIVE")
    .maybeSingle();
  if (memberError) return json({ error: "membership check unavailable" }, 503);
  if (!membership) return json({ error: "not a member of this group" }, 403);

  const { data: group } = await db.from("groups").select("name, base_currency").eq("id", groupId).maybeSingle();

  // Roster first: the member columns, and their order, are fixed for the whole file.
  const { data: memberRows, error: rosterError } = await db
    .from("members")
    .select("user_id, joined_at")
    .eq("group_id", groupId)
    .order("joined_at", { ascending: true });
  if (rosterError) return json({ error: "roster unavailable" }, 503);
  const memberIds: string[] = (memberRows ?? []).map((m) => m.user_id as string);

  const { data: userRows } = await db.from("users").select("id, display_name").in("id", memberIds.length ? memberIds : [""]);
  const nameById = new Map<string, string>();
  for (const u of userRows ?? []) nameById.set(u.id as string, (u.display_name as string) ?? "Someone");
  // Placeholders and departed members keep their column: leaving the group does not unspend the money
  // they were part of, and a file missing their column would not add up.
  const columnName = (id: string) => nameById.get(id) ?? "Deleted user";

  const { data: expenses, error: expenseError } = await db
    .from("expenses")
    .select("id, title, category_id, amount_subunits, currency, payer_user_id, expense_date, deleted_at")
    .eq("group_id", groupId)
    .is("deleted_at", null)
    .order("expense_date", { ascending: true });
  if (expenseError) return json({ error: "expenses unavailable" }, 503);

  const expenseIds = (expenses ?? []).map((e) => e.id as string);
  const { data: shares } = expenseIds.length
    ? await db.from("shares").select("expense_id, user_id, share_owed_subunits, deleted_at").in("expense_id", expenseIds).is("deleted_at", null)
    : { data: [] as Array<Record<string, unknown>> };

  const sharesByExpense = new Map<string, Array<{ userId: string; owed: number }>>();
  for (const s of shares ?? []) {
    const key = s.expense_id as string;
    const list = sharesByExpense.get(key) ?? [];
    list.push({ userId: s.user_id as string, owed: (s.share_owed_subunits as number) ?? 0 });
    sharesByExpense.set(key, list);
  }

  // Deliberately NOT filtered on deleted_at: on this table a soft-delete IS the void (see schema.sql),
  // so filtering them out would silently drop every reversal from the ledger.
  const { data: settlements } = await db
    .from("settlements")
    .select("from_user_id, to_user_id, payment_amount_subunits, payment_currency, settled_at, deleted_at")
    .eq("group_id", groupId)
    .order("settled_at", { ascending: true });

  const header = ["Date", "Type", "Description", "Currency", "Amount", "Paid by", ...memberIds.map(columnName), "Unassigned"];
  const lines: string[] = [csvRow(header)];
  const currencies = new Set<string>();
  const totals = new Map<string, number>();
  let unassignedTotal = 0;
  const addTotal = (userId: string, delta: number) => totals.set(userId, (totals.get(userId) ?? 0) + delta);

  for (const e of expenses ?? []) {
    const currency = (e.currency as string) ?? group?.base_currency ?? "USD";
    currencies.add(currency);
    const payer = e.payer_user_id as string | null;
    const total = (e.amount_subunits as number) ?? 0;
    const owedBy = new Map<string, number>();
    for (const s of sharesByExpense.get(e.id as string) ?? []) owedBy.set(s.userId, s.owed);

    const cells = memberIds.map((id) => {
      // Net for this row: what they put in, minus what the split says was theirs.
      const paid = id === payer ? total : 0;
      const net = paid - (owedBy.get(id) ?? 0);
      addTotal(id, net);
      return net === 0 ? "" : money(net);
    });
    // Whatever the split does not account for: unclaimed lines on an itemized bill, almost always.
    let assigned = 0;
    for (const owed of owedBy.values()) assigned += owed;
    const unassigned = total - assigned;
    unassignedTotal += unassigned;
    lines.push(csvRow([
      (e.expense_date as string) ?? "",
      "Expense",
      (e.title as string) ?? "",
      currency,
      money(total),
      payer ? columnName(payer) : "",
      ...cells,
      unassigned === 0 ? "" : money(-unassigned),
    ]));
  }

  for (const s of settlements ?? []) {
    // A voided settlement is kept in the file and marked, not dropped. It happened, someone undid it,
    // and a ledger that quietly omits reversals is the kind of thing people argue about.
    const voided = (s.deleted_at as number | null) != null;
    const currency = (s.payment_currency as string) ?? group?.base_currency ?? "USD";
    currencies.add(currency);
    const amount = (s.payment_amount_subunits as number) ?? 0;
    const from = s.from_user_id as string;
    const to = s.to_user_id as string;
    const cells = memberIds.map((id) => {
      // Paying someone moves your balance up and theirs down.
      const net = voided ? 0 : (id === from ? amount : id === to ? -amount : 0);
      if (net !== 0) addTotal(id, net);
      return net === 0 ? "" : money(net);
    });
    lines.push(csvRow([
      isoDate((s.settled_at as number) ?? 0),
      voided ? "Payment (voided)" : "Payment",
      `${columnName(from)} paid ${columnName(to)}`,
      currency,
      money(amount),
      columnName(from),
      ...cells,
      "",
    ]));
  }

  if (currencies.size <= 1) {
    lines.push(csvRow(["", "Total", "Net balance", [...currencies][0] ?? group?.base_currency ?? "USD", "", "",
      ...memberIds.map((id) => money(totals.get(id) ?? 0)), money(-unassignedTotal)]));
  } else {
    lines.push("");
    lines.push(csvRow(["", "Note", `Totals omitted: this group has expenses in ${[...currencies].sort().join(", ")}. Sum each currency separately.`]));
  }

  const safeName = (group?.name as string ?? "group").replace(/[^A-Za-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "") || "group";
  return new Response(lines.join("\n"), {
    status: 200,
    headers: {
      "Content-Type": "text/csv; charset=utf-8",
      "Content-Disposition": `attachment; filename="${safeName}-evenly.csv"`,
    },
  });
});
