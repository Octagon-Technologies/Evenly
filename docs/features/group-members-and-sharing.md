# Group members + sharing cleanup (T2, T9, T10)

**Status:** T9, T10 done; T2 blocked on mock sign-off
**Worktree / branch:** `fb/group-surface` (from `feat/split-by-items-flow`)
**Mockup:** `design/group-members-entry-mockup.html` — required for T2 before Compose.

Paths below are under `code/shared/src/commonMain/kotlin/app/splitevenly/` unless noted. Read
`ui/AGENTS.md` first.

---

## T2 · See and add members from the group page

**Problem.** A tester created a group, wanted to add an expense with friends, and their first
instinct was "how do I add someone to the group?" Today members are only listed in Group settings
(gear icon → `ui/screen/settings/GroupSettingsScreen.kt`, "Add member" row ~L245). The group page
(`ui/screen/group/GroupExpensesTab.kt`, top bar ~L157–172) has only Search / Share / Gear icons.

**User outcome.** On the group page, without opening settings, you can see who's in the group and
add someone in one tap. Secondary to "Add expense", but clearly visible.

**Proposed shape (to be confirmed in the mock).** A members strip directly under the top bar /
above the tab island: overlapping avatars + "4 members" + a "+ Add" pill. Tapping the strip opens a
**Members sheet** listing everyone (reuse the row rendering from GroupSettingsScreen; admin/left
chips) with two actions at the top: "Invite with link" (share/copy the group link, same as the
existing `InviteSheet`) and "Add by name" (the existing add-member/placeholder path,
`ui/screen/group/IncludeMemberSheet.kt` / settings `showAdd`). The share icon in the top bar can
then go — one front door, not two. Show 2–3 layout options in the mock.

**Acceptance criteria.**
- Given a group with only me, the strip reads "Just you · Add people" and is visually prominent
  (this is the tester's exact state).
- Given N members, the strip shows up to 4 avatars + "+N", and tapping opens the sheet.
- From the sheet, "Invite with link" reaches the native share sheet in ≤ 2 taps from the group page.
- Adding a member by name makes them immediately selectable in Add expense.
- Group settings keeps its members section (no regression), sharing the same composable.

**Non-goals.** No changes to join/claim logic, roles, or removing members.

---

## T9 · Remove "New link" (invite rotation)

**Problem.** "New link" invalidates the current invite. Testers don't understand it; the screenshot
shows it beside Share/Copy.

**Acceptance criteria.**
- "New link" is gone from both `GroupSettingsScreen.kt:181` and `GroupExpensesTab.kt:429`
  (`InviteSheet`). Share link + Copy link remain; lay them out as one row of two equal buttons.
- The `onRotateInvite` plumbing is removed from these screens' signatures and their Route wrappers;
  the repository `rotate` capability stays (future use) — do not delete data-layer code.

---

## T10 · Remove conflict reminders

**Scout finding.** The "Conflict reminders" Off/Daily/Weekly radios at
`GroupSettingsScreen.kt:250–268` are backed by `var reminder by remember { … }` (L111) — local
state, never persisted or sent. Nothing schedules a reminder. So this is a dead control.

**Acceptance criteria.**
- The "Conflict reminders" settings group and its `reminder` state are deleted.
- Any user-level "conflict reminders" toggle in Profile/notification settings (search
  `conflictReminders` in `ui/`) is removed from the UI.
- Do **not** touch `users.notify_conflict_reminders`, `groups.reminder_cadence`, the Room columns, or
  `push-notify` — schema stays (AGENTS.md §4.4/§4.5; removal would be a migration for no gain).

---

## Test plan

| Criterion | Proof |
| --- | --- |
| T2 strip states (1 member, N members), sheet actions | iOS sim run, light + dark; `ux-firsttimer` as Sam |
| T9/T10 removals | Compile both targets; grep shows no "New link" / "Conflict reminders" in `ui/` |
| Both targets | AGENTS.md §5 compile pair + `testAndroidHostTest` |

Commits: one per task (`feat(group): …` T2, `refactor(group): …` T9, `refactor(settings): …` T10).

## 7. Completion report

- **T9 done** (e335670): "New link" removed from GroupSettingsScreen and InviteSheet; Share + Copy are one row of two equal buttons; `onRotateInvite` removed from both screens and both Route wrappers. `rotateInviteToken` in the repository untouched.
- **T10 done** (b67a77e): Conflict reminders group + `reminder` state deleted. No user-level toggle exists in `ui/` (grep for conflictReminders/reminder is empty). Schema, Room, `push-notify` untouched.
- Both compile targets green for each commit (`:shared:compileAndroidMain`, `:shared:compileKotlinIosSimulatorArm64`). Tests and iOS sim run not performed for T9/T10 (deletions only); owner should eyeball Group settings and the invite sheet.
- **T2 blocked** on owner sign-off: mock at `design/group-members-entry-mockup.html` (options A strip, B chip row, C solo card, plus Members sheet). Open point: the 2-tap criterion implies "Invite with link" should open the native share sheet directly rather than InviteSheet. Recommend A.
- Outward-facing: none proposed.
