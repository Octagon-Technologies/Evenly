// Transactional email via Resend, shared by `waitlist` and `send-welcome-email`. Same env-optional,
// best-effort, never-throws shape as `slack.ts` / `posthogServer.ts`: unset means inert, so a signup
// or a waitlist join never fails because a mail provider hiccupped or isn't configured yet.
//
//   RESEND_API_KEY    the project's Resend API key
//   RESEND_FROM       "Andrew at Evenly <andrew@split-evenly.app>" — must be a verified Resend domain

import { welcomeEmail, waitlistEmail } from "./emailTemplates.ts";

const DEFAULT_FROM = "Andrew at Evenly <andrew@split-evenly.app>";

/** True when Resend accepted the message. Existing callers ignore it (best-effort); the debug OTP
 *  relay needs it because a silent failure there leaves the owner waiting on a code that never comes. */
async function sendEmail(to: string, subject: string, html: string): Promise<boolean> {
  const apiKey = Deno.env.get("RESEND_API_KEY");
  if (!apiKey) return false;
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
      return false;
    }
    return true;
  } catch (e) {
    console.error(`resend send failed: ${(e as Error).message}`);
    return false;
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

/** The OTP for a `@sharecost.test` persona, delivered to the owner's inbox by `debug-otp-relay`.
 *  Plain on purpose: it's a dev tool, and the subject carries the code so it reads from a notification. */
export async function sendDebugOtpEmail(inbox: string, persona: string, otp: string): Promise<boolean> {
  const subject = `${otp} is the Evenly code for ${persona}`;
  const html = `<p>Debug sign-in code for <b>${persona}</b>:</p>` +
    `<p style="font-size:28px;font-weight:700;letter-spacing:4px">${otp}</p>` +
    `<p>Relayed by the debug-otp-relay edge function. Only <code>@sharecost.test</code> addresses route here.</p>`;
  return await sendEmail(inbox, subject, html);
}
