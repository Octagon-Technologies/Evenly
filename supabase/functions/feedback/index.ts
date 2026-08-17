// Evenly feedback submission (ADMIN_FEEDBACK_SPEC.md §4). One form, three entry points, one table.
//
// This is the only writer to `feedback_tickets`, which has RLS on with no policies and grants revoked
// -- the same boundary `waitlist` is for `waitlist_signups`. The browser bundles ship no Supabase
// client and no anon key, so there is no other path to the table and there must not become one.
//
// `verify_jwt` is off, and has to be: two of the three entry points are anonymous strangers on a
// marketing page or a guest bill link with no Supabase session at all. The Authorization header is
// therefore OPTIONAL and verified here when present: a valid one turns the ticket into an identified
// one, an invalid one is refused outright rather than silently downgraded to anonymous, because a
// signed-in user whose token has expired should be told to sign in again and not have their bug
// report filed under "some stranger".
//
// Writes one row and returns `{ ok: true }` and nothing else. The client shows the thank-you screen
// off that; there is no ticket to read back (spec §7: v1 is write-only from the client).

import { createClient, type SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";
import { postToSlack } from "../_shared/slack.ts";
import { callerIp, isRateLimited, recordSubmission } from "../_shared/rateLimit.ts";

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "content-type, apikey, authorization",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
  });
}

// Kept in lockstep with the check constraints in `schema.sql`. Validated in both places on purpose:
// the constraint is the backstop that makes a bug here a 500 instead of a garbage row.
const TYPES = ["problem", "suggestion", "question"] as const;
const CATEGORIES = ["payments", "account", "missing_expense", "splitting", "design", "other"] as const;
const SOURCES = ["app_ios", "app_android", "web", "web_claim"] as const;

const WORD_CAP = 100;
/** Belt to the word cap's braces. 100 words of ordinary prose is ~700 characters; 4000 leaves room
 *  for someone pasting a long URL without letting a megabyte of text reach the table. */
const MESSAGE_CHAR_CAP = 4000;

const EMAIL = /^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/;

function countWords(text: string): number {
  const trimmed = text.trim();
  return trimmed ? trimmed.split(/\s+/).length : 0;
}

function serviceClient(): SupabaseClient {
  return createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false } },
  );
}

const TYPE_EMOJI: Record<string, string> = {
  problem: ":rotating_light:",
  suggestion: ":bulb:",
  question: ":grey_question:",
};

const CATEGORY_LABEL: Record<string, string> = {
  payments: "Payments",
  account: "Sign-in & account",
  missing_expense: "A missing or lost expense",
  splitting: "Splitting & balances",
  design: "The app's design",
  other: "Something else",
};

/**
 * One message per ticket: type, category, the first ~30 words, and a link to it in the dashboard
 * (spec §6). Deliberately NOT behind the `ops_alerts` cooldown -- that exists so one failing receipt
 * scan cannot spam a channel, but every ticket is a distinct human and dropping the fifth because
 * four arrived that minute is silently losing your users' words.
 */
async function pingSlack(ticketId: string, type: string, category: string, message: string): Promise<void> {
  const words = message.trim().split(/\s+/);
  const excerpt = words.slice(0, 30).join(" ") + (words.length > 30 ? "…" : "");
  const dashboard = Deno.env.get("ADMIN_DASHBOARD_URL")?.replace(/\/+$/, "");
  const link = dashboard ? `\n<${dashboard}/feedback/${ticketId}|Open in the dashboard>` : "";

  await postToSlack(
    Deno.env.get("SLACK_FEEDBACK_WEBHOOK_URL"),
    `${TYPE_EMOJI[type] ?? ""} *${type}* · ${CATEGORY_LABEL[category] ?? category}\n>${excerpt}${link}`,
  );
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS_HEADERS });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return json({ error: "bad_json" }, 400);
  }

  // Honeypot (spec §8). A field no human sees and no real client fills. Answered with the same
  // `{ ok: true }` a real submission gets, so a bot learns nothing about why it failed.
  if (typeof body.website === "string" && body.website.trim() !== "") {
    return json({ ok: true });
  }

  const type = String(body.type ?? "");
  const category = String(body.category ?? "");
  const source = String(body.source ?? "");
  if (!(TYPES as readonly string[]).includes(type)) return json({ error: "bad_type" }, 400);
  if (!(CATEGORIES as readonly string[]).includes(category)) return json({ error: "bad_category" }, 400);
  if (!(SOURCES as readonly string[]).includes(source)) return json({ error: "bad_source" }, 400);

  const message = typeof body.message === "string" ? body.message.trim() : "";
  if (!message) return json({ error: "empty_message" }, 400);
  if (message.length > MESSAGE_CHAR_CAP) return json({ error: "too_long" }, 400);
  // The client blocks typing at the cap rather than truncating on submit (spec §4.3), so reaching
  // here means a client that skipped the check. Refusing beats storing a half-sentence.
  if (countWords(message) > WORD_CAP) return json({ error: "too_long" }, 400);

  const sb = serviceClient();

  // Identity, if any. A present-but-bad token is a 401, never a silent downgrade to anonymous.
  const header = req.headers.get("Authorization") ?? "";
  const token = header.toLowerCase().startsWith("bearer ") ? header.slice(7).trim() : "";
  let userId: string | null = null;
  if (token) {
    const { data, error } = await sb.auth.getUser(token);
    if (error || !data.user) return json({ error: "unauthenticated" }, 401);
    userId = data.user.id;
  }

  // In-app submissions are authenticated by definition (spec §4.1). Accepting an app-sourced ticket
  // with no user would leave a row that claims to be from the app and cannot be replied to in it.
  const fromApp = source === "app_ios" || source === "app_android";
  if (fromApp && !userId) return json({ error: "unauthenticated" }, 401);

  // Rate limit per user when we have one, per IP otherwise (spec §8).
  const principal = userId ? `user:${userId}` : callerIp(req);
  if (await isRateLimited(sb, "feedback", principal)) return json({ error: "rate_limited" }, 429);

  // Name and email are web-only fields. In-app they are absent from the form entirely, and asking a
  // signed-in user to type their own email is exactly the friction this feature exists to remove --
  // so an app client sending them is ignored rather than trusted.
  const submitterName = !fromApp && typeof body.name === "string"
    ? body.name.trim().slice(0, 80) || null
    : null;
  const rawEmail = !fromApp && typeof body.email === "string" ? body.email.trim().slice(0, 254) : "";
  if (rawEmail && !EMAIL.test(rawEmail)) return json({ error: "bad_email" }, 400);
  const submitterEmail = rawEmail || null;

  const appVersion = fromApp && typeof body.appVersion === "string"
    ? body.appVersion.trim().slice(0, 40) || null
    : null;

  const { data: inserted, error } = await sb
    .from("feedback_tickets")
    .insert({
      user_id: userId,
      submitter_name: submitterName,
      submitter_email: submitterEmail,
      type,
      category,
      message,
      source,
      app_version: appVersion,
    })
    .select("id")
    .single();

  if (error) {
    // No message body, no email, no user id. "No PII in logs" (spec §8) is not negotiable and this is
    // the exact line where it would be broken by accident.
    console.error(`feedback: insert failed: ${error.message}`);
    return json({ error: "server_error" }, 500);
  }

  // Both best-effort and both after the row is safe: a Slack outage or a rate-limit log failure must
  // never turn a stored ticket into an error for the person who wrote it.
  await recordSubmission(sb, "feedback", principal);
  await pingSlack(inserted.id as string, type, category, message);

  return json({ ok: true });
});
