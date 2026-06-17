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
import da.chelimo.sharecost.domain.auth.NotificationPrefs
import da.chelimo.sharecost.domain.auth.ThemeMode
import da.chelimo.sharecost.ui.components.ScToggle
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** 18 · Profile & settings (design/src/screens-settings.jsx). */
@Composable
fun ProfileScreen(
    displayName: String = "Alex Rivera",
    email: String = "alex@hey.com",
    baseCurrency: String = "USD",
    paymentAppsSummary: String = "Venmo +2",
    onBack: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onEditPaymentApps: () -> Unit = {},
    onEditName: (String) -> Unit = {},
    onSendFeedback: () -> Unit = {},
    onPrivacy: () -> Unit = {},
    onTerms: () -> Unit = {},
    notifications: NotificationPrefs = NotificationPrefs(),
    onNotificationsChange: (NotificationPrefs) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var analytics by remember { mutableStateOf(true) }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(displayName) { mutableStateOf(displayName) }

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
                    ScAvatar(displayName, me = true, size = AvatarSize.Lg)
                    Column(Modifier.weight(1f)) {
                        if (editingName) {
                            ScTextField(nameDraft, { nameDraft = it }, placeholder = "Your name")
                        } else {
                            Text(displayName, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(email, color = c.ink2, fontSize = 12.sp)
                        }
                    }
                    if (editingName) {
                        ScIconButton(ScIcons.Check, { onEditName(nameDraft.trim()); editingName = false }, size = 20.dp, tint = c.blue)
                    } else {
                        ScIconButton(ScIcons.Edit, { nameDraft = displayName; editingName = true }, size = 20.dp, tint = c.ink2)
                    }
                }
            }

            // ── Preferences ────────────────────────────────────
            ProfileGroup("Preferences") {
                ProfileRow(icon = ScIcons.Globe, label = "Base currency", value = baseCurrency)
                ProfileRow(icon = ScIcons.Wallet, label = "Payment apps", value = paymentAppsSummary, last = true, onClick = onEditPaymentApps)
            }

            // ── Notifications ──────────────────────────────────
            ProfileGroup("Notifications") {
                NotifRow("New expenses", notifications.newExpenses) { onNotificationsChange(notifications.copy(newExpenses = it)) }
                NotifRow("Someone pays you", notifications.payments) { onNotificationsChange(notifications.copy(payments = it)) }
                NotifRow("Conflict reminders", notifications.conflictReminders, last = true) { onNotificationsChange(notifications.copy(conflictReminders = it)) }
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
                        selected = themeMode.name,
                        onSelect = { onThemeModeChange(ThemeMode.fromName(it)) },
                    )
                }
            }

            // ── Support ────────────────────────────────────────
            ProfileGroup("Support") {
                ProfileRow(icon = ScIcons.Comment, label = "Send feedback", onClick = onSendFeedback)
                ProfileRow(icon = ScIcons.Lock, label = "Privacy policy", onClick = onPrivacy)
                ProfileRow(icon = ScIcons.Info, label = "Terms of service", last = true, onClick = onTerms)
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

/** A notification toggle row (`.sc-row`), controlled by the caller so the value persists (F7). */
@Composable
private fun NotifRow(label: String, checked: Boolean, last: Boolean = false, onCheckedChange: (Boolean) -> Unit) {
    val c = ShareCostTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (!last) Modifier.topHairline(c.border) else Modifier)
            .clickable { onCheckedChange(!checked) }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
        ScToggle(checked, onCheckedChange)
    }
}

@Preview
@Composable
private fun ProfilePreview() {
    ShareCostTheme { ProfileScreen() }
}
