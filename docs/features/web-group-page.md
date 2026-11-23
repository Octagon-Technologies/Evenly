# Web group page: one person has the app, everyone else uses the link

**Status:** built on `feat/web-group-page`; migration `web_group_page` and `web-claim` deployed to the live project 2026-11-23. The web page itself ships with the next site deploy
**Owner agent/session:** unassigned
**Mockup:** `design/web-group-page-mockup.html` (required before any Compose or Svelte)

Paths below are under `code/shared/src/commonMain/kotlin/app/splitevenly/` unless noted. Read
`web/AGENTS.md`, `ui/AGENTS.md` and `supabase/AGENTS.md` before touching their trees.

---

## 1. Behavior brief

**Problem.** A group only works today if most of its members install Evenly. A friend without the
app can join a group (`/j/`, identity only) and claim items on one bill (`/b/`), but cannot see the
group, see what they owe, settle, fix a wrong expense, or log the dinner they paid for. So the one
person with the app ends up entering everything, and everyone else has to ask them what they owe.

**User outcome.** One person installs Evenly, shares one group link, and everyone else can see the
whole group, find and claim their restaurant items, settle up, edit expenses and add the ones they
paid for, from a browser.

**This deliberately reverses a recorded non-goal.** `WEB_CLAIM_SPEC.md` §1 and §9 and `web/AGENTS.md`
say the web is "not a web version of Evenly": no group view, no balances, no expense creation. The
owner reversed that on 2026-11-23. Those documents are rewritten in the same commit as slice 1, so a
later session does not "fix" the group page back out. What still holds from that spec: a web guest is
a placeholder member (§2.1), identity is group-scoped and durable (§2.2), recognition beats search
(§2.3), exact duplicate names are blocked (§2.4), the browser ships no Supabase client and every
write goes through the `web-claim` edge function, and the Kotlin split math is the authority.

**Non-goals.**
- Receipt scanning or OCR on the web. OCR costs money per call and the link is public.
- Web accounts, passwords, OTP or email. The browser token stays the whole session model.
- Editing group settings, other people's profiles, or removing members from the web.
- Offline web.
- A desktop layout. Wider screens get a centred mobile column.

**Platforms and systems affected.** `web/` (new group surface), `supabase/` (`web-claim` actions,
link token, possibly a settlement-confirm column), Android + iOS `ui/` (top bar, involved strip on
expense rows, notifications for guest activity), `domain/` only if the involved check is not already
derivable from `ExpenseItemUi`.

---

## 2. Decisions (agreed 2026-11-23)

| # | Question | Decision |
| --- | --- | --- |
| 1 | What does a guest see? | **Everything in the group**, same as an app member. "All" is the default with a **Mine** filter one tap away. Hiding expenses would look broken to someone sitting next to an app user who sees them all. |
| 2 | Editing (scenario 2) | Anyone may edit any expense. Changes apply instantly, are logged under the expense, can be undone by anyone, and the payer is notified in the app. **No approval queue**, same as the bill-line rule (`web/AGENTS.md`). Concurrent edits go through the existing field-level `merge_expense`. |
| 3 | Someone picking another person's name | No lock in v1. If a name is opened from a second browser, that person and the payer see a "used on another device" note. |
| 4 | Settling | "I paid Joe $42" counts immediately. Joe is notified and can dispute or remove it. |
| 5 | Creating expenses on the web | Split evenly, exact amounts, or itemized by typing the lines. No receipt scan. |
| 6 | Adding new people while creating | Allowed, with the same duplicate-name blocking as the claim flow. |
| 7 | A forwarded link | "Reset link" comes back inside Group settings (not on the group page). The repository `rotate` capability from T9 is still there. |
| 8 | Order | Three releases: **A** view + claim + settle + new top bar + involved strip; **B** editing; **C** creating. A alone covers scenarios 1 and 4. |

**One group link.** The group link opens the app if it's installed (existing universal link /
App Link) and the web group page if it isn't. The top-bar Share button and the members strip's
"Invite" share the **same** link, so there is still one link per group even with two buttons.

**The "involved" strip (app and web).** Every expense card you are part of (payer, a share, or an
item assigned to or claimed by you) has a 2dp brand-blue strip on its left edge. Cards you are not on
have none. No caption, legend or tooltip explains it; repetition teaches it. Blue is used because it
carries no owe/owed meaning (owed is green, credit is burnt amber, errors are red). A settled expense
you were on keeps its strip: it records involvement, not status.

**Top bar on the app's group page.** Back · emoji · group name · **Search · Share · Settings**.
Share opens the native share sheet with the group link directly (no intermediate sheet). This
reverses T2's "one front door" decision (`group-members-and-sharing.md`) on purpose: sharing is the
thing we now want people to do most.

---

## 3. Acceptance criteria

**Release A**
- Given a friend without the app opens the group link, when they pick their name (or type a new
  one), then they see the group page: their net position against each person, a Settle button, a
  "Waiting on you" card if any itemized bill lists them and they have unclaimed items, and the full
  expense list with All selected.
- Given Steve was not on Joe's restaurant bill, then Steve's "Waiting on you" card never mentions
  it, and the bill appears in All without a strip and is absent from Mine.
- Given a guest taps a "Waiting on you" bill, then the existing claim screen opens for that bill
  without asking who they are again.
- Given a guest records "I paid Joe $42", then balances update for everyone on the next sync, and
  Joe gets a push he can act on to dispute.
- Given a returning browser, then the token recognises the person and skips "Who are you?".
- Given the app's group page, then the top bar reads Search · Share · Settings, and Share reaches
  the native share sheet in one tap.
- Given any expense list in the app or on the web, then every expense the viewer is involved in
  has the strip and no other does.

**Release B**
- Given a guest edits an expense's amount, payer or people, then it applies at once, a log entry
  appears under it, anyone can undo it, and the payer is notified.

**Release C**
- Given a guest adds an expense (even, exact, or typed items), then it appears in the app on the
  next sync with correct shares, and adding a new name creates a placeholder with duplicate
  blocking.

**States that are not the happy path.**

| State | Expected behavior |
| --- | --- |
| Empty group (no expenses) | "Nothing here yet" with Add expense (release C) or "Ask {app user} to add the first one" (A/B) |
| Loading | Skeleton rows, never an empty state while loading (`loading-vs-empty-states.md`) |
| Error (server rejected) | Retry inline; no optimistic writes (`WEB_CLAIM_SPEC.md` §6) |
| Link reset | "This link was replaced. Ask {name} for the new one." |
| Name used on another device | Note on the page; nothing blocked |
| Stale local vs newer server row | Field-level merge, as in the app |

---

## 4. Constraints

- **Data model.** Any new column (for example a dispute flag on settlements) goes into
  `supabase/schema.sql` first (AGENTS.md §4.4). Soft delete only (§4.5).
- **Realtime.** The web polls through `web-claim`; never add tables to the realtime publication.
- **Money.** The web sends inputs; split results come from the vector-gated math, never from
  browser floats. A rule change lands on both sides in one commit.
- **Security.** RLS is still permissive, so the edge function is the whole boundary. Every new
  action checks the session token belongs to the group named by the link token. Rate-limit writes
  per session.

---

## 5. Scout findings

To fill in before slice 1: `web-claim` actions to extend (`invite-resolve`, `invite-name`,
`invite-claim`, `claim-placeholder` exist today); whether `/j/` becomes the group page or a new
`/g/` path is added (both must stay in the AASA / App Link manifests); where `ExpenseItemUi` gets
an `involved` flag; how push-notify is triggered for guest writes.

---

## 6. Slices

- [ ] A1 Top bar Search · Share · Settings (app)
- [ ] A2 Involved strip on expense rows (app)
- [ ] A3 Web group page: read-only list with All / Mine, strip, net position
- [ ] A4 "Waiting on you" card into the existing claim screen
- [ ] A5 Settle from the web + dispute in the app; rewrite `WEB_CLAIM_SPEC.md` §1/§9 and `web/AGENTS.md`
- [ ] B1 Web edit with log + undo + payer push
- [ ] C1 Web create: even + exact
- [ ] C2 Web create: typed itemized lines, new-name placeholders
- [ ] Reset link in Group settings

---

## 7. Completion report

**Built:** all three releases. App: Share in the group top bar, 3dp involved strip (`c.blueText`), Reset
link in Group settings, new share copy. Server: the RPCs in `supabase/AGENTS.md` "Web group page" plus five
`web-claim` actions (`group-view`, `group-pay`, `group-expense-create`, `-edit`, `-undo`). Web: the group
page, settle, expense detail with change log + undo, add/edit form, a balances port with tests.

**Verification:** SQL test green on a local Postgres 16 with the real table definitions; web `npm test`
(51) and `npm run typecheck` clean; web flow walked in the browser on the `?mock` fixture (pick name,
balances, Mine, settle, add with exact-split validation, edit, undo); shared compiles on Android and iOS;
`testAndroidHostTest` green including the new strip test.

**Deployed 2026-11-23:** migration `web_group_page` applied; `supabase/tests/web_group_page.sql` passed on the
live project (rolled back, no test rows left); no new function is executable by `anon` or `authenticated`;
`web-claim` deployed with `--use-api` (server-side bundle succeeded), and all five `group-*` actions answer an
unknown invite with `GONE`.

**Known gaps / unverified:** no browser has used the group page against a real group yet (that writes real
rows, so it waits for the owner). The site (`web/`) is not deployed, so the live `/j/` still shows the old
"you're in" screen until it is. `ux-firsttimer` not
yet run on the web page. Itemized bills created on the web get shares only after an app device syncs.
