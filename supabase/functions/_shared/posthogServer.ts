// Server-side PostHog capture, shared by edge functions that need to mirror something into PostHog
// that only the server ever computes (ANALYTICS_PLAN_C_JOURNEY_AND_CLAIMING.md §4 — the receipt-scan
// cost ledger). Same env-optional, best-effort, never-throws shape as `slack.ts`: unset means inert.
//
//   POSTHOG_API_KEY   the project's API key (same one the client SDKs use — not the personal API key)
//   POSTHOG_HOST       defaults to PostHog Cloud US, matching PostHogCredentials.HOST on the client

const DEFAULT_HOST = "https://us.i.posthog.com";

/**
 * One-shot server capture via PostHog's HTTP capture endpoint directly, rather than the `posthog-node`
 * SDK: that SDK batches and flushes on an interval/queue, which does not suit a one-request-per-invoke
 * edge function (a batched event can be lost if the isolate is torn down before the next flush).
 */
export async function captureServerEvent(
  distinctId: string,
  event: string,
  properties: Record<string, unknown>,
): Promise<void> {
  const apiKey = Deno.env.get("POSTHOG_API_KEY");
  if (!apiKey) return;
  const host = Deno.env.get("POSTHOG_HOST") ?? DEFAULT_HOST;
  try {
    await fetch(`${host}/i/v0/e/`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        api_key: apiKey,
        event,
        distinct_id: distinctId,
        properties,
      }),
    });
  } catch (e) {
    console.error(`posthog capture failed (${event}): ${(e as Error).message}`);
  }
}
