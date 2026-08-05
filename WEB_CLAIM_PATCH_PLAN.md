# Plan — patching up web claim, before `ux-firsttimer`

Status: **ready to build.** Owner decisions are locked in §2.
Branch target: `feat/web-claim` (continues from step 6).
Predecessor: `WEB_CLAIM_SPEC.md`. Steps 1 to 6 of its §11 are done; **this plan is what step 7 waits on.**

This exists because step 6 finished the build order but not the feature. Three things were true when it
landed: one spec'd behaviour had never been given to any step, one open question turned out to rest on a
concept that does not exist in the codebase, and nobody had looked at the three new screens.

---

## 1. What was actually missing

| Gap | Where it came from |
| --- | --- |
| **Guest as payer** (`§3.8`, E24, E25) | Step 3 deferred it as "needs its own careful pass"; step 6 was payer *screens*, this is a guest *write*. No step owned it. |
| **The approval gate on an unread bill** (`§12` Q1) | Twelve people correcting OCR on a freshly-scanned receipt generate twelve cards for a bill the payer never read. That is when a payer starts approving without looking. |
| **Nothing has been seen** | The three step-6 screens are compile- and logic-verified. Simulator tap injection never reached the Compose view, so no human or synthetic tap has touched them. |
| **`verified` is not persisted** | The obvious fix for Q1 was "gate dormant until the payer confirms the scan". There is no such point: `verified` lives only in editor UI state (`ItemizedBillState.showUnverifiedNotice`, `EditBillState.verified`), derived at scan time and gone when the bill saves. No column, nothing in the schema. |

---

## 2. Decisions taken

| Decision | Choice | Consequence |
| --- | --- | --- |
| Guest as payer | **Build it properly**, through causal `split_version` semantics | §3 P2, P5 |
| The edit gate | **Remove it.** Guest edits apply instantly; the payer is *told*, with Undo | §3 P1, P3, P4 |
| Who may undo | **Anyone on the bill** | §3 P4; see the tiebreak note below |
| Clearing the banner | **Read marker.** Opening the screen marks changes seen; the list stays as history | §3 P4 |
| Guest currency | **Bill's currency only, no conversion** | §3 P6 |
| `N left` on long lines | **Leave it.** Judge it in step 7 against a real receipt | not built |
| App verification route | **Android emulator** (`android-cli`) | §3 P7 |
| Landing the tree | **Two commits, step 6 last** | §3 P0 |

### Why removing the gate is not a loosening

The risk model here is honest mistakes, not bad faith: this is a group of friends splitting a dinner.
Against honest mistakes an **undo is worth exactly as much as an approval**, and it costs nothing in the
overwhelming case where the edit was fine. Approve-each taxes the 95% to catch the 5%, and the tax is
paid at the worst possible moment, with twelve people standing up to leave.

Everything that made the gate safe survives: every change is **attributed**, **reversible**, and
**recorded permanently**. Only the waiting goes.

### The tiebreak, since anyone may undo

There is deliberately no arbitration. An undo is itself an attributed entry in the log, and undoing an
already-undone change is a no-op (first-undo-wins, conditional update, same shape as `claim_placeholder`).
So the worst case is A edits, B undoes, A edits again, and every step of that is visible to everyone with
a name against it. **The log is the tiebreak.** Do not add a rights hierarchy on top.

---

## 3. Build order

Each step ends green on both platforms and is useful alone.

### P0 — Land the tree in two commits

The working tree carries step 6 **on top of** uncommitted step-2/step-5 leftovers
(`ui/navigation/BillRoutes.kt`, `ui/screen/bill/BillClaimScreen.kt`, `supabase/schema.sql`,
`data/AGENTS.md`, `iosTest/.../BillJoinPortionTest.kt`). Selective `git add` per `AGENTS.md` §6.2:
leftovers first, step 6 second.

**Confirm with the owner that the leftovers are finished work, not a parallel session's in-flight edit,**
before committing them. The concurrent-sessions hazard is real in this repo.

### P1 — Amend `WEB_CLAIM_SPEC.md` before writing code

Precedence says the spec is a source of truth, and `AGENTS.md` §8 says to **delete superseded lines in
place** rather than append a correction under a wrong sentence. This decision contradicts the spec in
seven places:

- **§2.7** — the whole section inverts. The surviving asymmetry is not approve-vs-instant; it is that an
  item edit moves *everyone's* money and therefore gets announced, while joining a claim moves two
  people's and does not. Keep the "do not harmonise these" instinct, restate what it now protects.
- **§3.6** — "creates a pending edit" becomes "applies, and is announced".
- **§3.9.1** — "Review changes" becomes "What changed". Approve/reject becomes Undo.
- **§10** — the decision-record row "Item editing | Full edit, payer approves each individually".
- **E19** — her total includes her edit because it *applied*, not because it is provisional. The "waiting
  for Andrew" state disappears.
- **E20** — two guests editing the same line is now last-write-wins on the item, both entries logged.
- **E22, E23** — E22 already described a state that cannot occur (an unapproved ADD has no item row and
  therefore no claims); under this model it is moot for a second reason. E23's REMOVE applies instantly.

Also update `§12`: Q1 is answered by this plan, Q3 by P6, Q2 stays open for step 7.

### P2 — One server-side Zone-2 write path

**This is the load-bearing new piece, and it is why P3 and P5 are one job rather than two.**

Both remaining writes are a guest changing Zone 2 of an expense from the edge function, where there is a
service key and no `auth.uid()`:

- an item edit changes `expenses.amount_subunits` and must advance `split_version`;
- naming the payer changes `payer_user_id`, guarded by the `split_version` the guest read (E25).

Skipping the version bump is not cosmetic: the next app push carrying a stale base would revert the guest's
change silently, which is precisely the failure `merge_expense`'s causal model exists to prevent
(`data/AGENTS.md`).

Build **`apply_web_bill_edit`** and **`set_web_bill_payer`**, both `security definer`, both service-role
only (explicit `revoke ... from anon, authenticated` — Supabase auto-grants EXECUTE at creation time
regardless of `revoke ... from public`; see `supabase/AGENTS.md`). `set_web_bill_payer` must implement the
same causal rule as `merge_expense`: base matches ⇒ apply and advance; base stale ⇒ refuse and return who
the payer now is. Calling `merge_expense` itself from the edge function is the alternative and means
constructing a full expense payload for a one-field change; prefer the dedicated RPC, but write it against
the same rule and say so in a comment.

### P3 — Guest edits apply instantly

- `supabase/functions/web-claim/index.ts` `actionEdit` (~line 693) stops being insert-only: it calls
  `apply_web_bill_edit`, and the `pending_item_edits` row lands already applied.
- `pending_item_edits.decision` vocabulary changes from `APPROVED | REJECTED` to `APPLIED | UNDONE`.
  Additive: same column, same table, no migration beyond comments. **Verify no rows exist in the wild
  first** (`select count(*) from pending_item_edits`) — if any do, they are pre-decision test data and
  should be stamped, not left ambiguous.
- `web/src/lib/lines.ts:27` filters undecided ADDs into the list as provisional lines. That whole
  treatment goes: an ADD is now a real line arriving on the next poll.
- `web/src/screens/ClaimList.svelte:93` says "waiting for {payer}". Remove it.
- Undo from the web (anyone on the bill, per §2) needs a `web-claim` action. It is the mirror of
  `actionEdit` and shares the same RPC.

### P4 — The app: "What changed", with Undo

Most of step 6's review screen survives. The cards, the before → after, the struck-through old value and
the copy are all still right; only the two buttons and the framing change.

- `ui/screen/bill/BillReviewEditsScreen.kt` → the two `DecisionChip`s become one `Undo`, and the amber
  `WaitingNote` ("the bill keeps its current amounts until you decide") is now false and must go.
- `data/repository/BillPendingEdits.kt` inverts: `applyApprovedEdit` moves server-side (P2), and the app
  gains `undo`, which restores `previous_*` on the item and stamps `decision = 'UNDONE'`.
- `ui/screen/bill/BillWebClaimEntry.kt`'s banner copy: "3 changes to review" is a call to action and is no
  longer one. Something like "3 changes to the bill".
- **Read marker**: device-local in `SecureStorage`, keyed by expense, exactly like `CLAIM_GUIDE_OPENS_KEY`
  in `ui/navigation/BillRoutes.kt`. Chosen over a synced column because a new column on a synced entity is
  the heaviest change in this codebase (`AGENTS.md` §4.4) and the cost of getting it wrong is all of that
  table's sync. The tradeoff is a second device nagging again; that is acceptable for a payer and the
  decision is reversible later.
- `iosTest/.../BillPendingEditTest.kt` is rewritten around apply/undo. The four properties it pins stay
  the right four: the change lands, undo restores exactly, `split_version` advances, and doing it twice
  changes nothing.

### P5 — Guest as payer

**Mock it first.** There is no payer control anywhere in `design/web-claim-mockup.html` or in
`web/src/screens/`, so §3.8 has never been drawn. `AGENTS.md` §7 says the design decision gets made in the
mock, not the PR.

Then: `set_web_bill_payer` (P2) → a `payer` action in `web-claim` → the control on the web surface → E25's
losing-guest copy ("Andrew is the payer now"), which is the only part of this with a genuinely awkward
failure state.

### P6 — The small ones

- **Currency**: confirm rather than build. `web/src/lib/money/` has no FX and the bundle ships no Supabase
  client, so bill-currency-only is almost certainly already true. Verify it, then write it down in
  `web/AGENTS.md` so it does not get "fixed" later.
- **`USE_LOCAL_WEB_CLAIM_HOST`** in `domain/expense/WebBillLink.kt` is currently `true`, pointing the QR at
  `127.0.0.1:5177`. It must be `false` before anything ships. Prose will not hold this; add a CI grep or a
  release-checklist line that fails loudly.

### P7 — Walk the app screens on the Android emulator

Via the `android-cli` skill. Tap through all three screens, screenshot each, fix what looks wrong.

**Be honest about what this proves.** It catches layout, copy and flow. It does not catch iOS-only
behaviour, and this repo's single most repeated iOS-only crash is a nav-arg type that compiles fine
(`ui/AGENTS.md`). The three new routes all take `String` args, so the known trap is avoided, but an iOS
look is still owed before the feature is called done.

### P8 — Hand off to `ux-firsttimer`

Spec §11 step 7: cold walk as Sam and Diego, on a real phone browser. Only start it once P0 to P7 are
green, or the walk will report friction that this plan was already going to remove.

---

## 4. Traps

Each of these is a real bug waiting in the work above, not a general caution.

1. **`pending_item_edits.item_id` is null for an ADD.** The apply path must write the created item's id
   back into that column, or Undo has nothing to target and an added line becomes unremovable.
2. **Undoing a REMOVE must un-tombstone the item *and* the claims that went with it** — but claims killed
   by the removal are indistinguishable from claims the owner killed themselves at the same moment. Scope
   the revival by the removal's exact `deleted_at` stamp, or an undo resurrects claims people had
   deliberately dropped.
3. **A server-side item edit that forgets `split_version` reverts itself** on the payer's next push. See P2.
4. **`revoke ... from public` does not revoke from `anon`.** Every new `security definer` function needs an
   explicit `revoke ... from anon` (`supabase/AGENTS.md`).
5. **Two revoked probe links exist** on "Dinner at Pax Mall" (`019fc382-aee3-...`) from step 6's live
   verification. Inert, and the schema forbids deleting them. Do not be surprised by them.
6. **The money vectors gate is not involved.** Nothing here changes split math, so
   `test-vectors/bill-split.json` should not need re-recording. If a change here makes it go red, that is a
   real divergence, not a stale vector, and must not be re-recorded to turn it green
   (`test-vectors/README.md`).

---

## 5. Not in scope

- **Notifying the payer out of band.** `push-notify` is deployed but inert until `FCM_SERVICE_ACCOUNT` is
  set. The banner is the notification.
- **Tightening RLS.** Still `for all to authenticated using (true)`, still a P0 before prod, still a
  separate job (`supabase/AGENTS.md`).
- **An approve-all, a rights hierarchy, or arbitration between two undos.** All three re-import the model
  §2 just removed.
- **Anything in spec §9.** A web app, expense creation from the web, offline web, and the rest stay out.
