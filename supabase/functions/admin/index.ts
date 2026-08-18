// Evenly admin dashboard backend (ADMIN_FEEDBACK_SPEC.md §2.3). This is the entire security boundary
// for the admin data, exactly as `web-claim` is for the claim flow and `waitlist` is for the signup
// list.
//
// **The browser check is cosmetic. The gate is here.** The admin bundle ships a Supabase client for
// ONE purpose -- Google OAuth, to obtain a JWT -- and reads no table with it. An admin dashboard
// reading PostgREST directly with an anon key is a public data breach wearing a login form.
//
// Every request:
//   1. carries `Authorization: Bearer <supabase JWT>` from the browser's OAuth session,
//   2. is verified against the auth server here,
//   3. is looked up in `admin_users` and **403s if absent, before touching any data**,
//   4. and only then reads with the service key, returning exactly the shape the view needs.
//
// Routing follows `web-claim`: one function, many actions, dispatched on the last path segment,
// because Supabase edge functions do no framework-level sub-routing. One function rather than several
// small ones so there is a single JWT-verification path to get right (spec §9).
//
// `verify_jwt` is off in config.toml. Not because this endpoint is public -- it is the opposite -- but
// because platform-level verification 401s the CORS preflight before any of the above runs, and
// because platform verification accepts the anon key as a valid JWT, which would prove nothing. The
// real check is `requireAdmin` below and it runs on every action without exception.

import { createClient, type SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

/** Locked to the admin origins when `ADMIN_ALLOWED_ORIGINS` is set (comma-separated), `*` otherwise.
 *  Authorization here is a bearer token rather than a cookie, so `*` is not itself a hole -- a hostile
 *  page has no way to obtain the JWT. The allowlist is cheap defence in depth, not the gate. */
function corsHeaders(req: Request): Record<string, string> {
  const configured = Deno.env.get("ADMIN_ALLOWED_ORIGINS");
  const origin = req.headers.get("origin") ?? "";
  let allow = "*";
  if (configured) {
    const allowed = configured.split(",").map((o) => o.trim()).filter(Boolean);
    allow = allowed.includes(origin) ? origin : allowed[0] ?? "*";
  }
  return {
    "Access-Control-Allow-Origin": allow,
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "content-type, apikey, authorization",
    "Vary": "Origin",
  };
}

function json(req: Request, body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...corsHeaders(req) },
  });
}

function err(req: Request, code: string, status: number, detail?: string): Response {
  return json(req, { error: code, detail }, status);
}

function serviceClient(): SupabaseClient {
  return createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false } },
  );
}

interface Admin {
  userId: string;
  email: string;
}

/**
 * The gate. Returns the admin, or a Response to send back verbatim, so every action opens with
 * `if (admin instanceof Response) return admin;` and there is no shape of the code where a query runs
 * first.
 *
 * `403` for a valid Google account that is not on the list, `401` for no token or a bad one. The
 * distinction is for the dashboard, which shows a signed-in stranger "this account has no access"
 * rather than bouncing them through the OAuth loop forever.
 */
async function requireAdmin(req: Request, sb: SupabaseClient): Promise<Admin | Response> {
  const header = req.headers.get("Authorization") ?? "";
  const token = header.toLowerCase().startsWith("bearer ") ? header.slice(7).trim() : "";
  if (!token) return err(req, "unauthenticated", 401);

  const { data, error } = await sb.auth.getUser(token);
  if (error || !data.user) return err(req, "unauthenticated", 401);

  const { data: row, error: lookupError } = await sb
    .from("admin_users")
    .select("user_id, email")
    .eq("user_id", data.user.id)
    .maybeSingle();

  if (lookupError) {
    // Fails closed: an unreadable allowlist is not an open door.
    console.error(`admin: allowlist lookup failed: ${lookupError.message}`);
    return err(req, "server_error", 500);
  }
  if (!row) {
    // No email, no user id, no PII in the log -- just the fact that someone tried.
    console.warn("admin: sign-in by a non-allowlisted account, refused");
    return err(req, "forbidden", 403);
  }

  return { userId: row.user_id, email: row.email };
}

// ── Waitlist (spec §3) ──────────────────────────────────────────────────────────────────────────

const PAGE_SIZE_MAX = 200;

interface WaitlistRow {
  id: string;
  email: string;
  source: string | null;
  created_at: string;
}

/** Newest first, optionally filtered by a substring of the address. Search hits `email_norm` rather
 *  than `email` so "SAM@" finds "sam@", which is what someone typing into a search box means. */
async function waitlistPage(
  sb: SupabaseClient,
  query: string,
  limit: number,
  offset: number,
): Promise<{ rows: WaitlistRow[]; total: number } | { error: string }> {
  let filtered = sb
    .from("waitlist_signups")
    .select("id, email, source, created_at", { count: "exact" });

  if (query) {
    // `%` and `_` are PostgREST `ilike` wildcards and `,`/`(`/`)` are its filter-expression syntax.
    // Stripping them keeps a search for "a_b" literal instead of quietly matching everything, and
    // keeps a typed comma from breaking out of the filter it lands in.
    const escaped = query.replace(/[%_,()\\]/g, "");
    if (escaped) filtered = filtered.ilike("email_norm", `%${escaped}%`);
  }

  // Ordering and paging after the filter: `.order()`/`.range()` narrow the builder to the transform
  // half of the API, where `.ilike` is no longer offered.
  const { data, error, count } = await filtered
    .order("created_at", { ascending: false })
    .range(offset, offset + limit - 1);
  if (error) return { error: error.message };
  return { rows: (data ?? []) as WaitlistRow[], total: count ?? 0 };
}

/** RFC 4180 field: quote always, double any embedded quote. Also prefixes the formula characters
 *  Excel and Sheets execute on open -- an address like `=cmd|...` in a CSV is a real attack on the
 *  person who exports it, and the whole point of this file is that it gets opened in a spreadsheet. */
function csvField(value: string | null): string {
  const raw = value ?? "";
  const guarded = /^[=+\-@\t\r]/.test(raw) ? `'${raw}` : raw;
  return `"${guarded.replace(/"/g, '""')}"`;
}

/** Under PostgREST's `max_rows` (1000, `config.toml`), so a page is never silently clipped by it. */
const EXPORT_PAGE = 500;

async function waitlistCsv(sb: SupabaseClient): Promise<string | { error: string }> {
  // Paged rather than one unbounded select: the export is the one read with no natural ceiling, and
  // a single 50k-row request is how an edge function meets its memory limit on launch day.
  //
  // The loop terminates on the server's exact `count`, NOT on "a short page means the end". PostgREST
  // silently truncates any page at `max_rows`, so a short page can mean a cap rather than the end of
  // the list, and an export that quietly stops at row 1000 is worse than one that fails: you would
  // mail the first thousand people and never know about the rest.
  const rows: WaitlistRow[] = [];
  let total = Infinity;
  for (let offset = 0; rows.length < total; offset += EXPORT_PAGE) {
    const { data, error, count } = await sb
      .from("waitlist_signups")
      .select("id, email, source, created_at", { count: "exact" })
      .order("created_at", { ascending: false })
      .range(offset, offset + EXPORT_PAGE - 1);
    if (error) return { error: error.message };
    total = count ?? rows.length;
    const batch = (data ?? []) as WaitlistRow[];
    if (batch.length === 0) break; // nothing came back; stop rather than spin
    rows.push(...batch);
  }

  const lines = ["email,source,signed_up_at"];
  for (const row of rows) {
    lines.push([csvField(row.email), csvField(row.source), csvField(row.created_at)].join(","));
  }
  return lines.join("\r\n");
}

/** 7 / 30 / 90 / all, the chart's range toggles (spec §3.2). Anything else is treated as 30 rather
 *  than refused: a bad range is a UI bug, not a reason to show an admin an error page. */
function rangeStart(range: unknown): string | null {
  const days = range === "7" || range === 7 ? 7 : range === "90" || range === 90 ? 90 : range === "all" ? null : 30;
  if (days === null) return null;
  const start = new Date();
  start.setUTCHours(0, 0, 0, 0);
  start.setUTCDate(start.getUTCDate() - (days - 1));
  return start.toISOString();
}

// ── Dispatch ────────────────────────────────────────────────────────────────────────────────────

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders(req) });
  if (req.method !== "POST") return err(req, "method_not_allowed", 405);

  const path = new URL(req.url).pathname.replace(/\/+$/, "");
  const action = path.split("/").pop() ?? "";

  const sb = serviceClient();

  // Before the body is even parsed, let alone any table read.
  const admin = await requireAdmin(req, sb);
  if (admin instanceof Response) return admin;

  let body: Record<string, unknown> = {};
  if (req.headers.get("content-length") !== "0") {
    try {
      body = (await req.json()) as Record<string, unknown>;
    } catch {
      body = {};
    }
  }

  switch (action) {
    /** Who am I, and am I still allowed in. The dashboard calls this on load so the shell can render
     *  the signed-in state without a second source of truth in the browser. */
    case "me":
      return json(req, { email: admin.email });

    case "waitlist": {
      const query = typeof body.q === "string" ? body.q.trim().slice(0, 120) : "";
      const limit = Math.min(Math.max(Number(body.limit) || 50, 1), PAGE_SIZE_MAX);
      const offset = Math.max(Number(body.offset) || 0, 0);
      const result = await waitlistPage(sb, query, limit, offset);
      if ("error" in result) {
        console.error(`admin: waitlist read failed: ${result.error}`);
        return err(req, "server_error", 500);
      }
      return json(req, { ...result, limit, offset });
    }

    case "waitlist-export": {
      const csv = await waitlistCsv(sb);
      if (typeof csv !== "string") {
        console.error(`admin: waitlist export failed: ${csv.error}`);
        return err(req, "server_error", 500);
      }
      // Returned as a body rather than a download: a browser cannot put an Authorization header on a
      // navigation, so the dashboard fetches this and makes the Blob itself.
      return new Response(csv, {
        status: 200,
        headers: { "Content-Type": "text/csv; charset=utf-8", ...corsHeaders(req) },
      });
    }

    case "waitlist-stats": {
      const { data, error } = await sb.rpc("admin_waitlist_stats", { p_from: rangeStart(body.range) });
      if (error) {
        console.error(`admin: waitlist stats failed: ${error.message}`);
        return err(req, "server_error", 500);
      }
      return json(req, data);
    }

    default:
      return err(req, "not_found", 404, `unknown action '${action}'`);
  }
});
