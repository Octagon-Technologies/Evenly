# Session brief: the feedback thank-you screen

**Paste this whole file into a fresh session.** It is self-contained: it assumes no memory of the
conversation that produced it.

---

## What you are being asked to do

Design the **thank-you screen** a person sees after submitting feedback to Evenly. Three variants,
one per feedback type. It needs to be animated and it needs to feel considered, because it is the
last thing someone sees after taking the trouble to tell you something.

**Produce a visual mock first and get it approved before writing any implementation code.** That is
a standing rule in this project (`AGENTS.md` §7): design decisions get made in the mock, not the PR.
An HTML mock in `design/` is the established format. Look at `design/waitlist-final.html` and
`design/web-claim-mockup.html` for how mocks are presented here (artboards with commentary, desktop
and mobile side by side).

Expect to iterate on the mock a few times. That is the point of the session.

---

## Context you need

**Evenly** is a mobile-first expense-splitting app. Friends share a group, log expenses, split them
(evenly, by share, or itemised off a photographed receipt), and settle up through whatever payment
app they already use. Kotlin Multiplatform, Android and iOS, plus a small Svelte web surface.

**The feedback feature** (spec: `ADMIN_FEEDBACK_SPEC.md`) is one short form reachable from three
places: inside the app when signed in, at `/feedback` on the web, and from the guest bill-claim page.
The form is four fields:

1. **Type** — Problem / Suggestion / Question
2. **Category** — Payments, Sign-in & account, A missing or lost expense, Splitting & balances,
   The app's design, Something else
3. **Message** — one text area, hard capped at 100 words
4. **Name** — optional, web only

**Type is what selects the thank-you variant.** That is the whole reason Type exists as a separate
field from Category.

**This screen will be built twice**: once in Svelte for the web, once in Compose Multiplatform for
the app. Design it so both are achievable. Avoid anything that depends on a web-only capability, and
keep the animation expressible with simple transforms and opacity rather than, say, an SVG filter
chain.

---

## The three variants

Draft copy below is **a starting point to react to, not approved text**. Rewriting it is expected.

### Problem

The person hit something broken. They may be annoyed. The job is to make them feel heard and
believed, and to be concrete about what happens next.

> **We've got this.**
> Your report is logged and a real person will read it. We'll email you when it's fixed.

Tone: calm, competent, not cheerful. No exclamation marks. Do not thank them for their patience.

**The promise is real and you may make it.** Resolving a ticket does notify the submitter
(`ADMIN_FEEDBACK_SPEC.md` §5.1), manually in v1. So "we'll email you when it's fixed" is honest.
Do **not** promise a timeframe, because none is committed to.

### Suggestion

They gave you something for free. The job is warmth and a little flattery, without gushing.

> **Noted, and thank you.**
> Ideas from people who actually use Evenly are how it gets better. We read every one.

Tone: warm, brief, slightly informal.

### Question

They want an answer. The job is to be clear that one is coming, and roughly how.

> **We'll get you an answer.**
> Your question is with us. We'll reply by email.

Tone: straightforward and practical.

---

## Animation direction

Ideas to push against, not a specification. One orchestrated moment beats several scattered effects.

- **Problem** — something that *settles*. A mark that draws itself and comes to rest; weight arriving.
  Read: this has been caught and put somewhere safe. Explicitly not celebratory; confetti after
  someone reports losing an expense is tone deaf.
- **Suggestion** — something that *lifts*. A small bloom or rise, brighter than the Problem variant.
  This is the one that may be a little delightful.
- **Question** — something that *travels*. A sense of the message going somewhere and a reply on its
  way back.

Constraints:

- Keep the whole thing under roughly 600ms. This screen sits between someone and the door.
- **Must respect `prefers-reduced-motion`** on web and its platform equivalent in the app. Under
  reduced motion the screen should still look finished, not merely static.
- The screen needs a way onward: back to where they were, in the app; somewhere sensible on the web.
  Do not leave a dead end.

---

## Rules this must satisfy

Non-negotiable in this codebase:

- **No em dashes in user-facing strings.** Enforced by a hook on Kotlin string literals; apply the
  same rule to the web copy and the mock. Use a full stop or a comma.
- **Run the `ux-firsttimer` skill** before calling any UI work done. Build-time gate, not just an
  audit tool.
- **Match the existing design system.** The web marketing surface uses Fraunces (serif display) and
  Rethink Sans (body), tokens scoped under `.site-root` in `web/src/marketing.css`, brand blue
  `#2563eb`. The app has its own Compose tokens. Read the relevant `AGENTS.md` before styling
  anything.
- **Mobile is the product surface.** Design mobile first; wider viewports get a centred column, not a
  different design.

---

## Questions worth settling in this session

1. **How different should the three variants actually be?** One layout with swapped copy and accent,
   or three genuinely distinct treatments? Distinct is more expressive and three times the work to
   build and maintain.
2. **Does the screen show what they submitted back to them?** A summary reassures ("yes, that is what
   I said") but adds visual weight to what should be a light moment.
3. **Ticket reference number?** Useful if they email later, meaningless if they never do.
4. **What is the onward action per surface?** In-app probably returns to where they were. On web,
   after a `/feedback` submission from a marketing visit, there may be nowhere obvious to go.
5. **Reply-time language.** "We'll reply by email" is safe. Anything with a number is a commitment.
6. **Does the anonymous web submitter get a different Problem variant?** If they left no email, "we'll
   email you when it's fixed" is a promise that cannot be kept. This one matters and is easy to miss.

Question 6 is the one most likely to be discovered late. Settle it early.

---

## Deliverable

1. An HTML mock in `design/`, all three variants, mobile and desktop, with the animation actually
   running so it can be judged rather than imagined.
2. A short note on what was decided and what is still open.
3. **No implementation until the mock is approved.**
