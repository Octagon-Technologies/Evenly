// The two lifecycle emails (waitlist confirmation, post-signup welcome), in the approved "Bloom"
// direction: dark ground, radial brand-blue glow, the same trio tile colors as the waitlist page and
// the marketing site's hero. Table-based markup with inline styles throughout, not the `<div>` layout
// the design mockup used, because email clients (Outlook desktop, Gmail app) strip or ignore a plain
// CSS layout that a browser would render fine.
//
// Copy here is a first-pass placeholder, not final. It exists to prove the layout renders with real
// text at real lengths; expect it to be rewritten wholesale in a separate pass.

export interface EmailContent {
  subject: string;
  html: string;
}

/** `firstName` ultimately comes from `auth.users.raw_user_meta_data` (Google/Apple's display name),
 *  which the account holder controls. Escaping it before it lands in the greeting is what stops
 *  someone naming themselves `<img src=x onerror=...>` from doing anything but printing literally. */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

const BRAND_BLUE = "#3762e3";
const BG = "#0b1220";
const CARD = "#131b2e";
const INK = "#eef2fb";
const MUTED = "#93a0bc";
const TILE_MUTED = "#8f9ab6";

/** The feather mark from the waitlist page's brand pill, as a table-safe inline SVG data URI would be
 *  stripped by half of Outlook; a tiny inline `<svg>` renders in every client that matters here
 *  (Gmail, Apple Mail, Outlook.com, most mobile clients) and falls back to nothing (not broken) where
 *  it doesn't. */
const WORDMARK = `
  <span style="display:inline-block;vertical-align:middle;margin-right:8px;width:20px;height:20px;">
    <svg width="20" height="20" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
      <path fill="${BRAND_BLUE}" d="M20.5 3.2c-7.4-.9-13 2.6-15.2 8.2-1 2.6-1 5-.6 6.6L3 20.7a1 1 0 1 0 1.4 1.4l1.7-1.7c1.6.4 4 .4 6.6-.6 5.6-2.2 9.1-7.8 8.2-15.2a1.4 1.4 0 0 0-.4-1.4z"/>
      <path d="M17.6 6.4 6.6 17.4M13.8 7.2h3.3v3.3M10.2 10.8h3.3v3.3" stroke="${BG}" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
    </svg>
  </span>`;

function tile(bg: string, fg: string, path: string, title: string, body: string): string {
  return `
    <td width="33.33%" valign="top" style="padding:0 5px;">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${CARD};border-radius:14px;">
        <tr><td style="padding:16px 12px;">
          <table role="presentation" cellpadding="0" cellspacing="0"><tr><td width="30" height="30" style="background:${bg};border-radius:9px;text-align:center;vertical-align:middle;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="${fg}" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block;vertical-align:middle;"><path d="${path}"/></svg>
          </td></tr></table>
          <div style="height:10px;line-height:10px;font-size:0;">&nbsp;</div>
          <div style="font-family:'Rethink Sans',Arial,sans-serif;font-size:13px;font-weight:700;color:${INK};margin-bottom:4px;">${title}</div>
          <div style="font-family:'Rethink Sans',Arial,sans-serif;font-size:12px;line-height:1.45;color:${TILE_MUTED};">${body}</div>
        </td></tr>
      </table>
    </td>`;
}

const ICON_RECEIPT = "M5.5 7h13l-1 12.2a2 2 0 0 1-2 1.8H8.5a2 2 0 0 1-2-1.8Z M9.5 7V4.8h5V7M4 7h16";
const ICON_LINK = "M12 12a9 9 0 1 1-4-7";
const ICON_SPLIT = "M12 3v18M5 8 3 12l2 4M19 8l2 4-2 4";

/** `mailto:`, not a suppression-list link: there is no unsubscribe endpoint or send-suppression table
 *  yet, so a fake `{{unsubscribe_url}}` would print literally in the sent mail. A reply is the honest
 *  v1, same call the codebase already made for feedback resolution ("v1 is manual"). */
function shell(eyebrow: string, heading: string, bodyHtml: string, opts: { preheader: string }): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="dark light">
<title>Evenly</title>
</head>
<body style="margin:0;padding:0;background:${BG};">
  <div style="display:none;max-height:0;overflow:hidden;opacity:0;">${opts.preheader}</div>
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${BG};">
    <tr><td align="center" style="padding:32px 16px;">
      <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="width:560px;max-width:100%;">
        <tr><td style="background:${BG};background-image:radial-gradient(120% 140% at 50% -20%, rgba(55,98,227,.35), transparent 60%);border-radius:20px 20px 0 0;padding:36px 30px 30px;">
          <table role="presentation" cellpadding="0" cellspacing="0"><tr><td style="font-family:'Rethink Sans',Arial,sans-serif;font-size:16px;font-weight:800;color:${INK};">
            ${WORDMARK}Evenly
          </td></tr></table>
          <div style="height:24px;line-height:24px;font-size:0;">&nbsp;</div>
          <div style="font-family:'Rethink Sans',Arial,sans-serif;font-size:12px;font-weight:700;letter-spacing:.1em;text-transform:uppercase;color:${MUTED};margin-bottom:10px;">${eyebrow}</div>
          <div style="font-family:'Rethink Sans',Arial,sans-serif;font-size:26px;line-height:1.2;font-weight:800;color:${INK};letter-spacing:-.01em;">${heading}</div>
        </td></tr>
        <tr><td style="background:${BG};padding:0 30px;">
          ${bodyHtml}
        </td></tr>
        <tr><td style="background:${BG};border-radius:0 0 20px 20px;padding:22px 30px 30px;border-top:1px solid rgba(255,255,255,.08);">
          <div style="font-family:'Rethink Sans',Arial,sans-serif;font-size:12px;line-height:1.7;color:#5b6685;">
            Evenly &middot; split-evenly.app<br>
            <a href="mailto:andrew@split-evenly.app?subject=Unsubscribe" style="color:#5b6685;">Unsubscribe</a>
          </div>
        </td></tr>
      </table>
    </td></tr>
  </table>
</body>
</html>`;
}

const FEATURE_ROW = `
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin:26px 0;">
    <tr>
      ${tile("#1e2a4d", "#7ba0ff", ICON_RECEIPT, "Snap a receipt", "Every line splits itself.")}
      ${tile("#12332f", "#67d9d0", ICON_LINK, "No app for them", "Friends claim from a link.")}
      ${tile("#242045", "#b9a6ff", ICON_SPLIT, "Stay square", "One balance, always current.")}
    </tr>
  </table>`;

function paragraph(text: string, lead = false): string {
  const size = lead ? "16.5px" : "15.5px";
  const color = lead ? INK : "#c9d1e5";
  return `<div style="font-family:'Rethink Sans',Arial,sans-serif;font-size:${size};line-height:1.65;color:${color};margin:0 0 18px;">${text}</div>`;
}

function ctaButton(label: string, url: string): string {
  return `
    <table role="presentation" cellpadding="0" cellspacing="0" style="margin:8px 0 28px;">
      <tr><td style="background:${BRAND_BLUE};border-radius:11px;">
        <a href="${url}" style="display:inline-block;padding:13px 24px;font-family:'Rethink Sans',Arial,sans-serif;font-size:14.5px;font-weight:700;color:#ffffff;text-decoration:none;">${label}</a>
      </td></tr>
    </table>`;
}

function signoff(name: string, role: string): string {
  return `
    <div style="font-family:'Rethink Sans',Arial,sans-serif;font-size:15px;font-weight:650;color:${INK};margin:4px 0 30px;">
      ${name}<br>
      <span style="font-weight:500;font-size:13.5px;color:${MUTED};">${role}</span>
    </div>`;
}

export function waitlistEmail(firstName: string | null): EmailContent {
  const hi = firstName ? `Hi ${escapeHtml(firstName)},` : "Hi,";
  const body = `
    ${paragraph(hi, true)}
    ${paragraph("Thanks for joining the Evenly waitlist. That one click tells me I'm not the only person tired of chasing friends for money after dinner, and honestly, that's a good feeling to get in an inbox.")}
    ${paragraph("I'm building Evenly to make splitting a bill as easy as taking a photo of it.")}
    ${FEATURE_ROW}
    ${paragraph("We'll email you the moment Evenly opens, likely within days now.")}
    ${signoff("Andrew", "Founder, Evenly")}
  `;
  return {
    subject: "You're on the list",
    html: shell("Waitlist", "You're on the list, and that means a lot.", body, {
      preheader: "Thanks for joining Evenly. We'll email you the moment it opens.",
    }),
  };
}

export function welcomeEmail(firstName: string | null): EmailContent {
  const hi = firstName ? `Hi ${escapeHtml(firstName)},` : "Hi,";
  const body = `
    ${paragraph(hi, true)}
    ${paragraph("Thank you for signing up. I started Evenly after one too many trips where the “who owes what” math happened on a napkin, and a good evening ended with everyone quietly annoyed over a few pounds nobody could quite remember.")}
    ${paragraph("My hope is that Evenly disappears into the background of your life with friends. You snap a receipt, everyone taps what they had, and the money part is just done. No spreadsheets, no guilt, no chasing.")}
    ${FEATURE_ROW}
    ${ctaButton("Open Evenly", "https://split-evenly.app")}
    ${paragraph("Thanks again for trusting us this early. If anything ever feels off, just reply, I read every one of these myself.")}
    ${signoff("Andrew", "Founder, Evenly")}
  `;
  return {
    subject: "Welcome to Evenly, truly",
    html: shell("You're in", "Welcome. I'm genuinely glad you're here.", body, {
      preheader: "Thank you for signing up, and a bit of why Evenly exists.",
    }),
  };
}
