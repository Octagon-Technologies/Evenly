// Slack webhook posting, shared by the edge functions that alert a human.
//
// Two channels, two env vars, deliberately separate (ADMIN_FEEDBACK_SPEC.md §6): a receipt-parser
// alert and a user saying their expense vanished want different reactions, and a channel you have
// learned to skim is worse than no channel.
//
//   SLACK_ALERT_WEBHOOK_URL     ops -- something is broken (extract-receipt, revenuecat-webhook)
//   SLACK_FEEDBACK_WEBHOOK_URL  support -- a person wrote in
//
// Both are optional. Unset means inert, never an error, same pattern as every other integration here.
//
// `extract-receipt` and `revenuecat-webhook` still carry their own inline copies of the cooldown
// version. They were left alone rather than migrated in this change: they are live, working, and
// redeploying two functions to delete twenty duplicated lines is risk with no user-visible payoff.
// Migrate them the next time either is touched for its own reasons.

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

/**
 * Best-effort post. Never throws and never blocks a decision already made for the caller: a Slack
 * outage must not turn a stored ticket into a 500 for the person who wrote it.
 */
export async function postToSlack(webhookUrl: string | undefined, text: string): Promise<void> {
  if (!webhookUrl) return;
  try {
    await fetch(webhookUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ text }),
    });
  } catch (e) {
    console.error(`slack post failed: ${(e as Error).message}`);
  }
}

const DEFAULT_COOLDOWN_MS = 60 * 60 * 1000;

/**
 * Post at most once per `kind` per cooldown window, so a burst of identical failures (every scan,
 * while an API key stays broken) posts once rather than once per request.
 *
 * **Only for machine failures.** Do not put user-submitted content behind this: every feedback ticket
 * is a distinct human, and dropping the fifth because four arrived that minute is silently losing
 * your users' words (spec §6). Inbound rate limiting is the lever for that.
 */
export async function postToSlackOnce(
  sb: SupabaseClient,
  kind: string,
  webhookUrl: string | undefined,
  text: string,
  cooldownMs: number = DEFAULT_COOLDOWN_MS,
): Promise<void> {
  if (!webhookUrl) return;

  const cooldownStart = new Date(Date.now() - cooldownMs).toISOString();
  const { count, error } = await sb
    .from("ops_alerts")
    .select("kind", { count: "exact", head: true })
    .eq("kind", kind)
    .gt("sent_at", cooldownStart);
  if (error) {
    console.error(`ops_alerts cooldown check failed: ${error.message}`);
    return; // can't confirm we're outside the cooldown, so don't risk spamming
  }
  if ((count ?? 0) > 0) return;

  await postToSlack(webhookUrl, text);
  await sb.from("ops_alerts").insert({ kind });
}
