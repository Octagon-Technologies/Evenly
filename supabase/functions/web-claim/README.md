# `web-claim` — claiming a bill from the web, no app, no account

The entire security boundary for `WEB_CLAIM_SPEC.md`. The browser bundle ships **no Supabase client and
no anon key**; every guest read and write goes through this one function, which holds the service key
and re-derives its own authorisation on every request, scoped to exactly the one bill its token names
(spec §4.1–§4.3). `supabase/config.toml` pins `verify_jwt = false` for this function — project-level JWT
verification would 401 every request before this function's own token check ever runs.

## Routing

One function, several actions, dispatched on the **last path segment** — `POST /web-claim/<action>`,
always a JSON body carrying `token` (the plaintext bill token from the QR/URL; never the session token
in a cookie, since identity here is a `localStorage` value the client sends explicitly, spec §2.2).

| Action              | Body                                                                 | What                                                              |
| -------------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `resolve`            | `{ token, sessionToken? }`                                            | Landing branch (spec §3.1): welcome-back / pick-name / name-entry / expired / gone |
| `name`                | `{ token, name, force? }`                                             | Submit a chosen name (spec §3.2): exact match blocks, fuzzy match suggests, otherwise creates a placeholder + session |
| `claim-placeholder`   | `{ token, placeholderUserId }`                                         | "That's me" on the evidence list (spec §2.3, §5.4)                |
| `bill`                | `{ token }`                                                             | Raw items/claims/shares/extras/participants **and `pendingEdits`** (spec §3.3) — **no computed total**, money is client-side (step 4). The proposals ship with the rows because spec E19 requires a guest's own unapproved addition to be in her total, marked as waiting |
| `claim`               | `{ token, sessionToken, itemId, quantity }`                            | Solo claim / un-claim a line (spec "+ I had this")                |
| `join`                | `{ token, sessionToken, itemId, portionId?, overClaimAck? }`           | Join a line someone else already claimed (spec "+ Add me", §5.3)  |
| `share`               | `{ token, sessionToken, itemId, memberUserIds, overClaimAck? }`        | Declare who had a line together (spec §3.4, the share sheet). Walks each named member through `join_item_portion` server-side, caller first; members must already be on this bill |
| `leave`               | `{ token, sessionToken, itemId }`                                      | Take myself off a line (spec §2.7, E12). Soft-deletes only the caller's own claim/share rows, so it needs no RPC — the partition invariant is never in play |
| `edit`                | `{ token, sessionToken, kind, itemId?, label?, quantity?, unitPriceSubunits? }` | Propose add/relabel/reprice/requantify/remove (spec §2.7, §3.6) — always lands as a **pending** edit |
| `done`                | `{ token, sessionToken, done }`                                        | "I'm done" / undo (a nudge-silencer, not a lock — spec E31)       |
| `release`             | `{ token, sessionToken }`                                              | "Not Purity? Use a different name" (spec E4, E5) — revokes this browser's session and drops back to the landing branch |

Every response is JSON; errors are `{ "error": "<CODE>", "detail"? }` with a matching HTTP status.
`GONE` (404) and `EXPIRED` (410) are both meant to be read as a dead link by the client — a rate-limit
trip also returns `EXPIRED` rather than a distinct error, per spec §4.5 ("exceeding a limit reads as
expired, not an error"). `CLAIMED_ELSEWHERE` (409) means the payer merged this placeholder into a real
account while the guest held the session (spec E10): she is still recognised, but the bill is read-only
from here on — `resolve` returns `state: "claimed_elsewhere"` and every write action refuses.

## Deliberately NOT implemented here

- **Payer-role assignment.** `expenses.payer_user_id` is Zone 2 of the `merge_expense` causal-version
  model (`supabase/AGENTS.md`) — a guest setting the payer needs to go through that RPC's semantics with
  the `split_version` they read, not a blind update. Needs its own careful pass.
- **Approving/rejecting a pending edit.** That's the payer's in-app screen (build-order step 6);
  `pending_item_edits` rows just sit there, decidable, until the app acts on them.
- **The QR/link lifecycle itself** (creating, extending, revoking `web_bill_links`). That's also an
  in-app, payer-only action (step 6) — this function only ever *reads* a link, never writes one.

## Security notes

- Every action re-resolves the token from scratch (`resolveLink`): exists, not revoked, not expired,
  bill not deleted, group not deleted (spec §4.2). Nothing is cached across requests.
- A session token resolves to a placeholder **scoped to the link's group**, not the bill — identity is
  group-scoped and durable (spec §2.2), so a guest recognised at Ramen night is still recognised at
  Sunday roast even after the Ramen night link expires.
- **The read-only gate lives in `resolveSession`, not in the landing action.** Once
  `members.placeholder_claim_completed_at` is stamped, every write path has to refuse (spec E10, §4.4);
  putting the check only where the landing screen is chosen left `claim`/`join`/`edit`/`done` writing as
  a placeholder that had already been folded into someone's account. Any new write action inherits the
  gate by going through `requireWritableSession`.
- **Identity writes are decided inside a lock, not around one.** `claim_web_placeholder` inserts the
  winning `web_sessions` row itself, and `create_web_placeholder` does the name check and all four
  inserts in one transaction. Both previously check-then-acted from here, so two browsers could become
  the same person, or both take the same new name (spec §2.4, §5.4, E3).
- `claim` writes only the caller's own `item_claims` row. `join` never writes another user's row
  directly — it calls the atomic `join_item_portion` RPC, same as the app (see `data/AGENTS.md`
  "Joining someone else's claim").
- Rate limits (spec §4.5): 120 writes/min per token (`web_claim_write_log`, best-effort — this is abuse
  mitigation, not a financial guardrail, so a logging hiccup must not lock a guest out of claiming their
  own dinner; each write also prunes that token's own rows older than 5 min, so the log doesn't grow for
  the life of a 72h link), 40 placeholders **per group** (the schema scopes placeholders to a group, not
  a bill — see `supabase/AGENTS.md`), 200 items per bill (existing + unresolved pending `ADD`s).
- Every write checks its own error and reports it. A guest gets no offline mode and no optimistic
  success (spec §6): if a claim did not land, the response has to say so, or she walks away from the
  table believing she is done.

## Deploy

```bash
supabase functions deploy web-claim --project-ref wfpfgbipjmkysalfmyub
```
