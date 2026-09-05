package app.splitevenly.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvEmojiPicker
import app.splitevenly.ui.components.EvField
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvRadio
import app.splitevenly.ui.components.EvSectionLabel
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvToggle
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * A member as shown on the Group settings roster. [role] is "Admin", "No account", "Left", or "".
 * "No account" replaced "Placeholder": placeholder is our word for it, not the user's.
 */
data class MemberRowUi(
    val userId: String,
    val name: String,
    val role: String = "",
    val isMe: Boolean = false,
)

/** 17 · Group settings (design/src/screens-settings.jsx). */
@Composable
fun GroupSettingsScreen(
    groupName: String = "Tulum Trip",
    groupEmoji: String = "🏝️",
    baseCurrency: String = "USD",
    members: List<MemberRowUi> =
        listOf(
            MemberRowUi("u1", "Alex Rivera", "Admin", isMe = true),
            MemberRowUi("u2", "Andrew Park", "Admin"),
            MemberRowUi("u3", "Bob Lin"),
            MemberRowUi("u4", "Maya Kapoor"),
            MemberRowUi("u5", "Tyler Reed", "No account"),
        ),
    inviteLink: String = "split-evenly.app/j/8Kk2-Tulum",
    storageUsedBytes: Long = 212L * 1024 * 1024,
    onBack: () -> Unit = {},
    onAddMember: (name: String, addToPast: Boolean) -> Unit = { _, _ -> },
    onRename: (name: String, emoji: String) -> Unit = { _, _ -> },
    onCopyInvite: () -> Unit = {},
    onShareInvite: () -> Unit = {},
    onRotateInvite: () -> Unit = {},
    onRemoveMember: (MemberRowUi) -> Unit = {},
    onReconcile: () -> Unit = {},
    /** Names in this group with no account that the viewer hasn't answered. 0 hides the claim row. */
    unclaimedNameCount: Int = 0,
    onEditCategories: () -> Unit = {},
    // Null until Pro state has loaded, and on a group that has never held a pass: there is nothing
    // truthful to say yet, so the row is absent rather than empty (PRO_PASS_SPEC.md §8.3).
    proStatus: ProStatusUi? = null,
    onProClick: () -> Unit = {},
    // Export (PRO_PASS_SPEC.md §3). The row stays live without Pro and explains on tap; [exportNote]
    // carries the trailing hint ("Pro") or the outcome of the last attempt.
    exporting: Boolean = false,
    exportNote: String? = null,
    onExportCsv: () -> Unit = {},
    onArchive: () -> Unit = {},
    onLeave: () -> Unit = {},
    /**
     * What deleting would cost, counted from the real group. Loaded by the Route when the screen
     * opens rather than when the sheet does, so the sheet never renders a half-empty set of facts.
     */
    deleteImpact: GroupDeleteImpactUi = GroupDeleteImpactUi(),
    onDelete: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    var reminder by remember { mutableStateOf("Weekly") }
    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<MemberRowUi?>(null) }

    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        EvTopBar(
            title = "Group settings",
            navIcon = { EvIconButton(EvIcons.Back, onBack) },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Above "About": who paid for this group's Pro is a fact about the group, and burying it
            // under the settings list would defeat the point of naming them at all.
            proStatus?.let { ProStatusRow(status = it, onClick = onProClick) }

            // ── About ──────────────────────────────────────────
            SettingsGroup("About") {
                SettingsRow(icon = EvIcons.Sparkle, label = "Emoji & name", value = if (groupEmoji.isBlank()) groupName else "$groupEmoji $groupName", onClick = {
                    showRename =
                        true
                })
                SettingsRow(icon = EvIcons.Globe, label = "Base currency", value = baseCurrency, last = true)
            }
            Text(
                "Changing base currency re-converts past expenses at today's rate.",
                modifier = Modifier.offset(y = (-8).dp).padding(horizontal = 4.dp),
                style = t.caption,
            )

            // ── Share group ────────────────────────────────────
            SettingsGroup("Share group") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(c.surface)
                                .border(1.dp, c.border, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            EvIcon(EvIcons.Qr, size = 40.dp, tint = c.ink)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Invite link", style = t.itemTitle)
                            Text(
                                inviteLink,
                                style = t.caption.copy(color = c.ink2, fontFamily = EvenlyTheme.monoFamily),
                            )
                        }
                    }
                    EvButton(
                        "Share link",
                        onShareInvite,
                        leadingIcon = EvIcons.Share,
                        small = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EvButton(
                            "Copy link",
                            onCopyInvite,
                            modifier = Modifier.weight(1f),
                            variant = ButtonVariant.Secondary,
                            leadingIcon = EvIcons.Copy,
                            small = true,
                        )
                        EvButton(
                            "New link",
                            onRotateInvite,
                            modifier = Modifier.weight(1f),
                            variant = ButtonVariant.Secondary,
                            leadingIcon = EvIcons.Reload,
                            small = true,
                        )
                    }
                }
            }

            // ── Members ────────────────────────────────────────
            SettingsGroup("Members · ${members.size}") {
                // First child of the card, above the roster, because it reads as an action ON this list
                // when it sits inside the list. Hidden entirely when there is nothing to claim, so it
                // never sends anyone to an empty screen.
                if (unclaimedNameCount > 0) {
                    SettingsRow(
                        icon = EvIcons.Users,
                        label = "Claim a name as me",
                        value = "$unclaimedNameCount ${if (unclaimedNameCount == 1) "name" else "names"} here have no account",
                        onClick = onReconcile,
                    )
                }
                members.forEachIndexed { i, m ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(if (i > 0 || unclaimedNameCount > 0) Modifier.topHairline(c.border) else Modifier)
                                .clickable(enabled = !m.isMe) { removeTarget = m }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EvAvatar(m.name, me = m.isMe, size = AvatarSize.Sm)
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, style = t.itemTitle)
                            if (m.isMe) Text(" · you", style = t.itemTitle.copy(color = c.ink2, fontWeight = FontWeight.Medium))
                        }
                        if (m.role.isNotEmpty()) {
                            EvChip(
                                m.role,
                                variant =
                                    when (m.role) {
                                        "Admin" -> ChipVariant.Blue
                                        "Left" -> ChipVariant.Red
                                        else -> ChipVariant.Amber
                                    },
                            )
                        }
                        EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.ink3)
                    }
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(if (members.isNotEmpty()) Modifier.topHairline(c.border) else Modifier)
                            .clickable { showAdd = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EvIcon(EvIcons.Plus, size = 18.dp, tint = c.blueText)
                    Text("Add member", style = t.itemTitle.copy(color = c.blueText))
                }
            }

            // ── Conflict reminders ─────────────────────────────
            SettingsGroup("Conflict reminders") {
                listOf("Off", "Daily", "Weekly").forEachIndexed { i, r ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(if (i > 0) Modifier.topHairline(c.border) else Modifier)
                                .clickable { reminder = r }
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(r, style = t.itemTitle, modifier = Modifier.weight(1f))
                        EvRadio(selected = reminder == r)
                    }
                }
            }

            // ── Categories ─────────────────────────────────────
            SettingsGroup("Categories") {
                SettingsRow(icon = EvIcons.Tag, label = "Edit categories", value = "8 active", last = true, onClick = onEditCategories)
            }

            // ── Storage ────────────────────────────────────────
            SettingsGroup("Storage") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Receipts & images",
                            style = t.itemTitle,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${formatBytes(storageUsedBytes)} used", style = t.caption.copy(color = c.ink2, fontFamily = EvenlyTheme.monoFamily))
                    }
                }
            }

            // ── Export ─────────────────────────────────────────
            // Was three rows (CSV, JSON, PDF), none of which had an onClick: three buttons that did
            // nothing, which is the dead end AGENTS.md §7 forbids. One row that works replaces them.
            // The row stays tappable without Pro and explains on tap rather than greying out.
            SettingsGroup("Export") {
                SettingsRow(
                    icon = EvIcons.Download,
                    label = if (exporting) "Preparing your file…" else "Export CSV",
                    value = exportNote,
                    last = true,
                    // Live while exporting too, so a double tap is a no-op rather than a dead control.
                    onClick = { if (!exporting) onExportCsv() },
                )
            }

            // ── Danger zone ────────────────────────────────────
            // Each subtitle answers the only question that matters here: WHO does this affect. Archive
            // is you, Leave is you, Delete is everyone, and nothing about the three labels says so.
            SettingsGroup("Danger zone") {
                SettingsRow(
                    icon = EvIcons.Archive,
                    label = "Archive group",
                    subtitle = "Hides it from your list. Nobody else is affected.",
                    onClick = onArchive,
                )
                SettingsRow(
                    icon = EvIcons.Back,
                    label = "Leave group",
                    subtitle = "You leave. The group stays for everyone else.",
                    danger = true,
                    showChevron = false,
                    onClick = onLeave,
                )
                SettingsRow(
                    icon = EvIcons.Trash,
                    label = "Delete group",
                    subtitle = "Deletes it for everyone. 30 days to undo.",
                    danger = true,
                    last = true,
                    showChevron = false,
                    onClick = { showDelete = true },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var addToPast by remember { mutableStateOf(false) }
        EvModalScaffold(onDismiss = { showAdd = false }) {
            Text("Add a member", style = t.sheetTitle, modifier = Modifier.padding(bottom = 12.dp))
            EvField("Name") { EvTextField(name, { name = it }, placeholder = "e.g. Tyler") }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Add to all past expenses", style = t.itemTitle)
                    Text("Even splits update automatically; others become conflicts to resolve.", style = t.caption)
                }
                EvToggle(addToPast, { addToPast = it })
            }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                EvButton("Add", {
                    if (name.isNotBlank()) {
                        onAddMember(name.trim(), addToPast)
                        showAdd = false
                    }
                }, enabled = name.isNotBlank())
            }
        }
    }

    if (showRename) {
        var draft by remember { mutableStateOf(groupName) }
        var draftEmoji by remember { mutableStateOf(groupEmoji) }
        EvModalScaffold(onDismiss = { showRename = false }) {
            Text("Emoji & name", style = t.sheetTitle, modifier = Modifier.padding(bottom = 12.dp))
            EvField("Emoji") { EvEmojiPicker(selected = draftEmoji, onSelect = { draftEmoji = it }) }
            Spacer(Modifier.height(16.dp))
            EvField("Name") { EvTextField(draft, { draft = it }, placeholder = "Group name") }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                EvButton(
                    "Save",
                    {
                        if (draft.isNotBlank()) {
                            onRename(draft.trim(), draftEmoji)
                            showRename = false
                        }
                    },
                    enabled = draft.isNotBlank(),
                )
            }
        }
    }

    if (showDelete) {
        DeleteGroupSheet(
            groupName = groupName,
            impact = deleteImpact,
            onDismiss = { showDelete = false },
            onConfirm = {
                showDelete = false
                onDelete()
            },
        )
    }

    removeTarget?.let { target ->
        EvModalScaffold(onDismiss = { removeTarget = null }) {
            Text(
                "Remove ${target.name}?",
                style = t.sheetTitle,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text("They'll be removed from the group. Their past expenses and balances stay intact.", style = t.description)
            Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                EvButton("Remove", {
                    onRemoveMember(target)
                    removeTarget = null
                }, variant = ButtonVariant.Danger)
            }
        }
    }
}

/**
 * Humane byte size: integer B/KB/MB, one decimal for GB (e.g. "212 MB", "1.4 GB"). There's no enforced
 * quota, so this renders just the amount — the Storage row appends " used".
 */
private fun formatBytes(bytes: Long): String {
    val kb = 1024L
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> {
            val tenths = (bytes * 10 + gb / 2) / gb // round to one decimal
            "${tenths / 10}.${tenths % 10} GB"
        }

        bytes >= mb -> {
            "${(bytes + mb / 2) / mb} MB"
        }

        bytes >= kb -> {
            "${(bytes + kb / 2) / kb} KB"
        }

        else -> {
            "$bytes B"
        }
    }
}

/** The JSX `Group` helper — a section label over a clipped `.sc-card` of rows. */
@Composable
private fun SettingsGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Column {
        EvSectionLabel(label)
        EvCard { content() }
    }
}

/**
 * The facts a delete confirmation states, all counted from the real group.
 *
 * [proPassNote] is pre-formatted by the Route because it carries a date and this screen is DI-free.
 * It is present only when the group holds a live Evenly Pro pass, and it says the pass is not
 * refunded, because the pass deliberately survives the purge and its owner deserves to hear that
 * before the delete rather than after.
 */
data class GroupDeleteImpactUi(
    val memberCount: Int = 0,
    val expenseCount: Int = 0,
    val receiptCount: Int = 0,
    val unsettledCount: Int = 0,
    val proPassNote: String? = null,
)

/**
 * "Delete for everyone", gated behind typing the group's name.
 *
 * The friction is deliberate and unconditional. Any active member can delete, the delete removes five
 * people's shared financial history at once, and the two rows above this one in the danger zone
 * ("Archive", "Leave") are both harmless and adjacent. Typing the name is the cheapest gate that
 * cannot be passed by muscle memory.
 */
@Composable
private fun DeleteGroupSheet(
    groupName: String,
    impact: GroupDeleteImpactUi,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    var typed by remember { mutableStateOf("") }
    var showMismatch by remember { mutableStateOf(false) }
    val matches = typed.trim().equals(groupName.trim(), ignoreCase = true)

    EvModalScaffold(onDismiss = onDismiss) {
        Text("Delete $groupName?", style = t.sheetTitle)
        Text(
            // Falls back to the count-free wording rather than printing "all 0 members" in the window
            // between the sheet opening and the counts landing. A confirmation that states a visibly
            // wrong fact undoes the work the rest of this sheet is doing.
            if (impact.memberCount > 0) {
                "This deletes the group for all ${impact.memberCount} " +
                    "${if (impact.memberCount == 1) "member" else "members"}, not just you."
            } else {
                "This deletes the group for everyone in it, not just you."
            },
            style = t.description,
            modifier = Modifier.padding(top = 6.dp),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.surface)
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // Suppressed entirely on an empty group: "0 expenses go with it" is a true sentence that
            // reads like a glitch, and the header above already says what deleting costs.
            if (impact.expenseCount > 0 || impact.receiptCount > 0) {
                ImpactLine(
                    EvIcons.Receipt,
                    "${impact.expenseCount} ${if (impact.expenseCount == 1) "expense" else "expenses"}" +
                        if (impact.receiptCount > 0) {
                            " and ${impact.receiptCount} ${if (impact.receiptCount == 1) "receipt" else "receipts"} go with it"
                        } else {
                            " go with it"
                        },
                )
            }
            // Only when money is actually outstanding, and the only line that gets the warning color.
            if (impact.unsettledCount > 0) {
                ImpactLine(
                    EvIcons.Alert,
                    "${impact.unsettledCount} ${if (impact.unsettledCount == 1) "balance is" else "balances are"} " +
                        "still unsettled. Nobody will be able to see or settle them.",
                    tint = c.credit,
                )
            }
            ImpactLine(EvIcons.Clock, "Anyone in the group can restore it for 30 days")
            ImpactLine(EvIcons.Trash, "After that it is gone for good, including from our servers")
            impact.proPassNote?.let { ImpactLine(EvIcons.Star, it, tint = c.credit) }
        }

        Text(
            "Type $groupName to confirm",
            style = t.fieldLabel,
            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
        )
        EvTextField(
            typed,
            {
                typed = it
                showMismatch = false
            },
            placeholder = "Group name",
        )
        // The button stays live and explains on tap rather than greying out (ui/AGENTS.md: never a
        // silent dead end). A disabled button here would leave someone tapping a dead control with no
        // idea the field above it is what is stopping them.
        if (showMismatch) {
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EvIcon(EvIcons.Alert, size = 14.dp, tint = c.danger)
                Text("That does not match. Type $groupName exactly.", style = t.caption.copy(color = c.danger))
            }
        }

        Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            EvButton(
                "Delete for everyone",
                { if (matches) onConfirm() else showMismatch = true },
                variant = ButtonVariant.Danger,
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            EvButton("Cancel", onDismiss, variant = ButtonVariant.Secondary)
        }
    }
}

@Composable
private fun ImpactLine(
    icon: ImageVector,
    text: String,
    tint: Color = EvenlyTheme.colors.ink3,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        EvIcon(icon, size = 15.dp, tint = tint, modifier = Modifier.padding(top = 2.dp))
        Text(text, style = t.description)
    }
}

/**
 * The JSX `SetRow` — icon + label + optional value + chevron (or red, chevron-less for danger).
 *
 * [subtitle] exists for the danger zone, where three rows do three very different things to three
 * different sets of people and the labels alone cannot say which.
 */
@Composable
private fun SettingsRow(
    label: String,
    icon: ImageVector? = null,
    value: String? = null,
    subtitle: String? = null,
    danger: Boolean = false,
    last: Boolean = false,
    showChevron: Boolean = true,
    onClick: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    val fg = if (danger) c.danger else c.ink
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (!last) Modifier.topHairline(c.border) else Modifier)
                .clickable(onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { EvIcon(it, size = 20.dp, tint = if (danger) c.danger else c.ink2) }
        Column(Modifier.weight(1f)) {
            Text(label, style = t.rowTitle.copy(color = fg), textAlign = TextAlign.Start)
            subtitle?.let { Text(it, style = t.caption, modifier = Modifier.padding(top = 2.dp)) }
        }
        value?.let { Text(it, style = t.rowValue) }
        if (!danger && showChevron) EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.ink3)
    }
}

@Preview
@Composable
private fun GroupSettingsPreview() {
    EvenlyTheme { GroupSettingsScreen() }
}
