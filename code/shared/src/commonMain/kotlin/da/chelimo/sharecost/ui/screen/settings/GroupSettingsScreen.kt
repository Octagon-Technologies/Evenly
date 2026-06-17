package da.chelimo.sharecost.ui.screen.settings

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
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScModalScaffold
import da.chelimo.sharecost.ui.components.ScProgress
import da.chelimo.sharecost.ui.components.ScRadio
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.ScToggle
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** A member as shown on the Group settings roster. [role] is "Admin", "Placeholder", or "". */
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
        MemberRowUi("u5", "Tyler Reed", "Placeholder"),
    ),
    inviteLink: String = "sharecost.app/j/8Kk2-Tulum",
    onBack: () -> Unit = {},
    onAddMember: (name: String, addToPast: Boolean) -> Unit = { _, _ -> },
    onRename: (String) -> Unit = {},
    onCopyInvite: () -> Unit = {},
    onRotateInvite: () -> Unit = {},
    onRemoveMember: (MemberRowUi) -> Unit = {},
    onReconcile: () -> Unit = {},
    onArchive: () -> Unit = {},
    onLeave: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var reminder by remember { mutableStateOf("Weekly") }
    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<MemberRowUi?>(null) }

    Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        ScTopBar(
            title = "Group settings",
            navIcon = { ScIconButton(ScIcons.Back, onBack) },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── About ──────────────────────────────────────────
            SettingsGroup("About") {
                SettingsRow(icon = ScIcons.Sparkle, label = "Emoji & name", value = "$groupEmoji $groupName", onClick = { showRename = true })
                SettingsRow(icon = ScIcons.Globe, label = "Base currency", value = baseCurrency, last = true)
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
                            ScIcon(ScIcons.Qr, size = 40.dp, tint = c.ink)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Invite link", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                inviteLink,
                                color = c.ink2,
                                fontSize = 12.sp,
                                fontFamily = ShareCostTheme.monoFamily,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScButton(
                            "Copy link",
                            onCopyInvite,
                            modifier = Modifier.weight(1f),
                            variant = ButtonVariant.Secondary,
                            leadingIcon = ScIcons.Copy,
                            small = true,
                        )
                        ScButton(
                            "Rotate",
                            onRotateInvite,
                            modifier = Modifier.weight(1f),
                            variant = ButtonVariant.Secondary,
                            leadingIcon = ScIcons.Reload,
                            small = true,
                        )
                    }
                }
            }

            // ── Members ────────────────────────────────────────
            SettingsGroup("Members · ${members.size}") {
                members.forEachIndexed { i, m ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (i > 0) Modifier.topHairline(c.border) else Modifier)
                            .clickable(enabled = !m.isMe) { removeTarget = m }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ScAvatar(m.name, me = m.isMe, size = AvatarSize.Sm)
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (m.isMe) Text(" · you", color = c.ink2, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                        if (m.role.isNotEmpty()) {
                            ScChip(
                                m.role,
                                variant = when (m.role) {
                                    "Admin" -> ChipVariant.Blue
                                    "Left" -> ChipVariant.Red
                                    else -> ChipVariant.Amber
                                },
                            )
                        }
                        ScIcon(ScIcons.ChevR, size = 15.dp, tint = c.ink3)
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
                    ScIcon(ScIcons.Plus, size = 18.dp, tint = c.blue)
                    Text("Add member", color = c.blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Reconcile ──────────────────────────────────────
            SettingsGroup("Reconcile") {
                SettingsRow(icon = ScIcons.Users, label = "Claim a past member as me", last = true, onClick = onReconcile)
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
                        ScRadio(selected = reminder == r)
                    }
                }
            }

            // ── Categories ─────────────────────────────────────
            SettingsGroup("Categories") {
                SettingsRow(icon = ScIcons.Tag, label = "Edit categories", value = "8 active", last = true)
            }

            // ── Storage ────────────────────────────────────────
            SettingsGroup("Storage") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Receipts & images", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("212 MB of 1 GB", color = c.ink2, fontSize = 12.sp, fontFamily = ShareCostTheme.monoFamily)
                    }
                    ScProgress(fraction = 0.21f)
                }
            }

            // ── Export ─────────────────────────────────────────
            SettingsGroup("Export") {
                SettingsRow(icon = ScIcons.Download, label = "Export CSV")
                SettingsRow(icon = ScIcons.Download, label = "Export JSON")
                SettingsRow(icon = ScIcons.Download, label = "Export PDF", last = true)
            }

            // ── Danger zone ────────────────────────────────────
            SettingsGroup("Danger zone") {
                SettingsRow(icon = ScIcons.Archive, label = "Archive group", onClick = onArchive)
                SettingsRow(icon = ScIcons.Back, label = "Leave group", danger = true, last = true, showChevron = false, onClick = onLeave)
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var addToPast by remember { mutableStateOf(false) }
        ScModalScaffold(onDismiss = { showAdd = false }) {
            Text("Add a member", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            ScField("Name") { ScTextField(name, { name = it }, placeholder = "e.g. Tyler") }
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Add to all past expenses", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Even splits update automatically; others become conflicts to resolve.", color = c.ink2, fontSize = 12.sp)
                }
                ScToggle(addToPast, { addToPast = it })
            }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                ScButton("Add", { if (name.isNotBlank()) { onAddMember(name.trim(), addToPast); showAdd = false } }, enabled = name.isNotBlank())
            }
        }
    }

    if (showRename) {
        var draft by remember { mutableStateOf(groupName) }
        ScModalScaffold(onDismiss = { showRename = false }) {
            Text("Rename group", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            ScField("Name") { ScTextField(draft, { draft = it }, placeholder = "Group name") }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                ScButton("Save", { if (draft.isNotBlank()) { onRename(draft.trim()); showRename = false } }, enabled = draft.isNotBlank())
            }
        }
    }

    removeTarget?.let { target ->
        ScModalScaffold(onDismiss = { removeTarget = null }) {
            Text("Remove ${target.name}?", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            Text("They'll be removed from the group. Their past expenses and balances stay intact.", color = c.ink2, fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                ScButton("Remove", { onRemoveMember(target); removeTarget = null }, variant = ButtonVariant.Danger)
            }
        }
    }
}

/** The JSX `Group` helper — a section label over a clipped `.sc-card` of rows. */
@Composable
private fun SettingsGroup(label: String, content: @Composable () -> Unit) {
    Column {
        ScSectionLabel(label)
        ScCard { content() }
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
    val c = ShareCostTheme.colors
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
        icon?.let { ScIcon(it, size = 20.dp, tint = if (danger) c.danger else c.ink2) }
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
        value?.let { Text(it, color = c.ink2, fontSize = 14.sp) }
        if (!danger && showChevron) ScIcon(ScIcons.ChevR, size = 15.dp, tint = c.ink3)
    }
}

@Preview
@Composable
private fun GroupSettingsPreview() {
    ShareCostTheme { GroupSettingsScreen() }
}
