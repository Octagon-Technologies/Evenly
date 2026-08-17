// Per-principal rate limiting for the public, unauthenticated endpoints (ADMIN_FEEDBACK_SPEC.md §8).
//
// 30 submissions per hour. One every two minutes sustained is already far past human, and 30 leaves
// ordinary use untouched. Shared IPs are the thing to watch -- a cafe, office or campus NAT puts many
// real people behind one address -- and 30/hour is generous enough that this stays theoretical, which
// is what makes it a good number. If it ever bites, lengthen the window rather than lowering the cap.
//
// Storage is `public_write_log`, mirroring `web_claim_write_log` and `receipt_scan_log`: insert-only,
// service-role-only, counted in a rolling window. That pattern exists twice already.

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

export const RATE_LIMIT_PER_HOUR = 30;
const WINDOW_MS = 60 * 60 * 1000;

/**
 * The caller's IP, as the platform reports it. Returns null when no header is present, which is what
 * makes the caller's decision explicit rather than silently rate-limiting everyone as "unknown".
 */
export function callerIp(req: Request): string | null {
  const forwarded = req.headers.get("x-forwarded-for");
  if (forwarded) {
    // Left-most entry is the original client; the rest are proxies we added.
    const first = forwarded.split(",")[0]?.trim();
    if (first) return first;
  }
  return req.headers.get("cf-connecting-ip") ?? req.headers.get("x-real-ip");
}

/**
 * sha256 of `kind:principal`, hex. The log stores this and never the address itself: an IP is
 * personal data under the "no PII in logs" rule, and the only question this table answers is "same
 * caller again?", which a hash answers exactly as well. Salted by `kind` so the same visitor's
 * waitlist and feedback traffic are counted in separate buckets.
 */
async function principalHash(kind: string, principal: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${kind}:${principal}`));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * True when this principal is over the cap and the request should be refused with 429.
 *
 * **Fails closed on a broken count and open on a missing principal**, and the asymmetry is deliberate.
 * A failed query means the limiter cannot do its job, so it refuses; a request with no usable IP at
 * all is a platform quirk, and turning that into a blanket refusal would take the whole endpoint down
 * for everyone rather than protecting it.
 */
export async function isRateLimited(
  sb: SupabaseClient,
  kind: string,
  principal: string | null,
): Promise<boolean> {
  if (!principal) return false;

  const hash = await principalHash(kind, principal);
  const windowStart = new Date(Date.now() - WINDOW_MS).toISOString();

  const { count, error } = await sb
    .from("public_write_log")
    .select("id", { count: "exact", head: true })
    .eq("kind", kind)
    .eq("principal_hash", hash)
    .gt("created_at", windowStart);

  if (error) {
    console.error(`${kind}: rate-limit count failed: ${error.message}`);
    return true;
  }
  return (count ?? 0) >= RATE_LIMIT_PER_HOUR;
}

/** Records one accepted submission against the window. Best-effort: a failed log must not undo a
 *  write that already succeeded, it only makes this caller's next few requests cheaper. */
export async function recordSubmission(
  sb: SupabaseClient,
  kind: string,
  principal: string | null,
): Promise<void> {
  if (!principal) return;
  const hash = await principalHash(kind, principal);
  const { error } = await sb.from("public_write_log").insert({ kind, principal_hash: hash });
  if (error) console.error(`${kind}: rate-limit log insert failed: ${error.message}`);
}
