package da.chelimo.sharecost.ui.screen.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScSegmented
import da.chelimo.sharecost.ui.components.ScToggle
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** 18 · Profile & settings (design/src/screens-settings.jsx). */
@Composable
fun ProfileScreen(
    onBack: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var analytics by remember { mutableStateOf(true) }
    var appearance by remember { mutableStateOf("System") }

    Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        ScTopBar(
            title = "Profile",
            navIcon = { ScIconButton(ScIcons.Back, onBack) },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Account header card ────────────────────────────
            ScCard(padded = true) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScAvatar("Alex Rivera", me = true, size = AvatarSize.Lg)
                    Column(Modifier.weight(1f)) {
                        Text("Alex Rivera", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("alex@hey.com", color = c.ink2, fontSize = 12.sp)
                    }
                    ScIconButton(ScIcons.Edit, {}, size = 20.dp, tint = c.ink2)
                }
            }

            // ── Preferences ────────────────────────────────────
            ProfileGroup("Preferences") {
                ProfileRow(icon = ScIcons.Globe, label = "Base currency", value = "USD")
                ProfileRow(icon = ScIcons.Wallet, label = "Payment apps", value = "Venmo +2", last = true)
            }

            // ── Notifications ──────────────────────────────────
            ProfileGroup("Notifications") {
                NotifRow("New expenses", on = true)
                NotifRow("Someone pays you", on = true)
                NotifRow("Conflict reminders", on = false, last = true)
            }

            // ── Privacy ────────────────────────────────────────
            ProfileGroup("Privacy") {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { analytics = !analytics }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScIcon(ScIcons.Chart, size = 20.dp, tint = c.ink2)
                    Column(Modifier.weight(1f)) {
                        Text("Anonymous analytics", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Never includes expense details", color = c.ink2, fontSize = 12.sp)
                    }
                    ScToggle(analytics, { analytics = it })
                }
            }

            // ── Appearance ─────────────────────────────────────
            ProfileGroup("Appearance") {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    ScSegmented(
                        options = listOf("System", "Light", "Dark"),
                        selected = appearance,
                        onSelect = { appearance = it },
                    )
                }
            }

            // ── Support ────────────────────────────────────────
            ProfileGroup("Support") {
                ProfileRow(icon = ScIcons.Comment, label = "Send feedback")
                ProfileRow(icon = ScIcons.Lock, label = "Privacy policy")
                ProfileRow(icon = ScIcons.Info, label = "Terms of service", last = true)
            }

            // ── Account ────────────────────────────────────────
            ProfileGroup("Account") {
                ProfileRow(icon = ScIcons.Back, label = "Sign out", onClick = onSignOut)
                ProfileRow(icon = ScIcons.Trash, label = "Delete account", danger = true, last = true, showChevron = false)
            }

            Text(
                "ShareCost v2.4.0",
                modifier = Modifier.fillMaxWidth(),
                color = c.ink2,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The JSX `Group` helper — a section label over a clipped `.sc-card` of rows. */
@Composable
private fun ProfileGroup(label: String, content: @Composable () -> Unit) {
    Column {
        ScSectionLabel(label)
        ScCard { content() }
    }
}

/** The JSX `SetRow` — icon + label + optional value + chevron (or red, chevron-less for danger). */
@Composable
private fun ProfileRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
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

/** A notification toggle row (`.sc-row` with its own local checked state). */
@Composable
private fun NotifRow(label: String, on: Boolean, last: Boolean = false) {
    val c = ShareCostTheme.colors
    var checked by remember { mutableStateOf(on) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (!last) Modifier.topHairline(c.border) else Modifier)
            .clickable { checked = !checked }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
        ScToggle(checked, { checked = it })
    }
}

@Preview
@Composable
private fun ProfilePreview() {
    ShareCostTheme { ProfileScreen() }
}
