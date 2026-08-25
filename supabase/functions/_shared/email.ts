// Transactional email via Resend, shared by `waitlist` and `send-welcome-email`. Same env-optional,
// best-effort, never-throws shape as `slack.ts` / `posthogServer.ts`: unset means inert, so a signup
// or a waitlist join never fails because a mail provider hiccupped or isn't configured yet.
//
//   RESEND_API_KEY    the project's Resend API key
//   RESEND_FROM       "Andrew at Evenly <andrew@split-evenly.app>" — must be a verified Resend domain

import { welcomeEmail, waitlistEmail } from "./emailTemplates.ts";

const DEFAULT_FROM = "Andrew at Evenly <andrew@split-evenly.app>";

async function sendEmail(to: string, subject: string, html: string): Promise<void> {
  const apiKey = Deno.env.get("RESEND_API_KEY");
  if (!apiKey) return;
  const from = Deno.env.get("RESEND_FROM") ?? DEFAULT_FROM;
  try {
    const res = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${apiKey}`,
      },
      body: JSON.stringify({ from, to, subject, html }),
    });
    if (!res.ok) {
      console.error(`resend send failed (${res.status}): ${await res.text()}`);
    }
  } catch (e) {
    console.error(`resend send failed: ${(e as Error).message}`);
  }
}

/** First name only, so "Hi Sam," not "Hi sam smith,". Null when there's nothing to work with
 *  (a bare email, or a display name that's just an email address itself). */
function firstNameFrom(displayName: string | null | undefined): string | null {
  const trimmed = (displayName ?? "").trim();
  if (!trimmed || trimmed.includes("@")) return null;
  const first = trimmed.split(/\s+/)[0];
  return first ? first.charAt(0).toUpperCase() + first.slice(1) : null;
}

export async function sendWaitlistEmail(to: string): Promise<void> {
  const { subject, html } = waitlistEmail(null);
  await sendEmail(to, subject, html);
}

export async function sendWelcomeEmail(to: string, displayName?: string | null): Promise<void> {
  const { subject, html } = welcomeEmail(firstNameFrom(displayName));
  await sendEmail(to, subject, html);
}
