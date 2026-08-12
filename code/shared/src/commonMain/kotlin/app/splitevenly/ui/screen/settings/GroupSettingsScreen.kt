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
data class MemberRowUi(val userId: String, val name: String, val role: String = "", val isMe: Boolean = false)

/** 17 · Group settings (design/src/screens-settings.jsx). */
@Composable
fun GroupSettingsScreen(
    groupName: String = "Tulum Trip",
    groupEmoji: String = "🏝️",
    baseCurrency: String = "USD",
    members: List<MemberRowUi> = listOf(
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
    onRename: (String) -> Unit = {},
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
) {
    val c = EvenlyTheme.colors
    var reminder by remember { mutableStateOf("Weekly") }
    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
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
                SettingsRow(icon = EvIcons.Sparkle, label = "Emoji & name", value = "$groupEmoji $groupName", onClick = { showRename = true })
                SettingsRow(icon = EvIcons.Globe, label = "Base currency", value = baseCurrency, last = true)
            }
            Text(
                "Changing base currency re-converts past expenses at today's rate.",
                modifier = Modifier.offset(y = (-8).dp).padding(horizontal = 4.dp),
                color = c.ink2,
                fontSize = 12.sp,
            )

            // ── Share group ────────────────────────────────────
            SettingsGroup("Share group") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)
                                .border(1.dp, c.border, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            EvIcon(EvIcons.Qr, size = 40.dp, tint = c.ink)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Invite link", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                inviteLink,
                                color = c.ink2,
                                fontSize = 12.sp,
                                fontFamily = EvenlyTheme.monoFamily,
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
                        modifier = Modifier.fillMaxWidth()
                            .then(if (i > 0 || unclaimedNameCount > 0) Modifier.topHairline(c.border) else Modifier)
                            .clickable(enabled = !m.isMe) { removeTarget = m }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EvAvatar(m.name, me = m.isMe, size = AvatarSize.Sm)
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (m.isMe) Text(" · you", color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                        if (m.role.isNotEmpty()) {
                            EvChip(
                                m.role,
                                variant = when (m.role) {
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
                    modifier = Modifier.fillMaxWidth()
                        .then(if (members.isNotEmpty()) Modifier.topHairline(c.border) else Modifier)
                        .clickable { showAdd = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EvIcon(EvIcons.Plus, size = 18.dp, tint = c.blueText)
                    Text("Add member", color = c.blueText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Conflict reminders ─────────────────────────────
            SettingsGroup("Conflict reminders") {
                listOf("Off", "Daily", "Weekly").forEachIndexed { i, r ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (i > 0) Modifier.topHairline(c.border) else Modifier)
                            .clickable { reminder = r }
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(r, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
                        Text("Receipts & images", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${formatBytes(storageUsedBytes)} used", color = c.ink2, fontSize = 12.sp, fontFamily = EvenlyTheme.monoFamily)
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
            SettingsGroup("Danger zone") {
                SettingsRow(icon = EvIcons.Archive, label = "Archive group", onClick = onArchive)
                SettingsRow(icon = EvIcons.Back, label = "Leave group", danger = true, last = true, showChevron = false, onClick = onLeave)
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var addToPast by remember { mutableStateOf(false) }
        EvModalScaffold(onDismiss = { showAdd = false }) {
            Text("Add a member", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            EvField("Name") { EvTextField(name, { name = it }, placeholder = "e.g. Tyler") }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Add to all past expenses", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Even splits update automatically; others become conflicts to resolve.", color = c.ink2, fontSize = 12.sp)
                }
                EvToggle(addToPast, { addToPast = it })
            }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                EvButton("Add", { if (name.isNotBlank()) { onAddMember(name.trim(), addToPast); showAdd = false } }, enabled = name.isNotBlank())
            }
        }
    }

    if (showRename) {
        var draft by remember { mutableStateOf(groupName) }
        EvModalScaffold(onDismiss = { showRename = false }) {
            Text("Rename group", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            EvField("Name") { EvTextField(draft, { draft = it }, placeholder = "Group name") }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                EvButton("Save", { if (draft.isNotBlank()) { onRename(draft.trim()); showRename = false } }, enabled = draft.isNotBlank())
            }
        }
    }

    removeTarget?.let { target ->
        EvModalScaffold(onDismiss = { removeTarget = null }) {
            Text("Remove ${target.name}?", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            Text("They'll be removed from the group. Their past expenses and balances stay intact.", color = c.ink2, fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                EvButton("Remove", { onRemoveMember(target); removeTarget = null }, variant = ButtonVariant.Danger)
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
        bytes >= mb -> "${(bytes + mb / 2) / mb} MB"
        bytes >= kb -> "${(bytes + kb / 2) / kb} KB"
        else -> "$bytes B"
    }
}

/** The JSX `Group` helper — a section label over a clipped `.sc-card` of rows. */
@Composable
private fun SettingsGroup(label: String, content: @Composable () -> Unit) {
    Column {
        EvSectionLabel(label)
        EvCard { content() }
    }
}

/** The JSX `SetRow` — icon + label + optional value + chevron (or red, chevron-less for danger). */
@Composable
private fun SettingsRow(
    label: String,
    icon: ImageVector? = null,
    value: String? = null,
    danger: Boolean = false,
    last: Boolean = false,
    showChevron: Boolean = true,
    onClick: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    val fg = if (danger) c.danger else c.ink
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (!last) Modifier.topHairline(c.border) else Modifier)
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { EvIcon(it, size = 20.dp, tint = if (danger) c.danger else c.ink2) }
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
        value?.let { Text(it, color = c.ink2, fontSize = 14.sp) }
        if (!danger && showChevron) EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.ink3)
    }
}

@Preview
@Composable
private fun GroupSettingsPreview() {
    EvenlyTheme { GroupSettingsScreen() }
}
