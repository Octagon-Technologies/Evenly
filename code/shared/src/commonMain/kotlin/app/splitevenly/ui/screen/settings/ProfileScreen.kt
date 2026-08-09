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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvModalScaffold
import app.splitevenly.ui.components.EvSectionLabel
import app.splitevenly.ui.components.EvSegmented
import app.splitevenly.domain.auth.NotificationPrefs
import app.splitevenly.domain.auth.ThemeMode
import app.splitevenly.ui.components.EvToggle
import app.splitevenly.ui.components.EvTextField
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/** 18 · Profile & settings (design/src/screens-settings.jsx). */
@Composable
fun ProfileScreen(
    displayName: String = "Alex Rivera",
    email: String = "alex@hey.com",
    baseCurrency: String = "USD",
    paymentAppsSummary: String = "Venmo +2",
    paymentAppsSet: Boolean = true,
    isSignedIn: Boolean = true,
    onSignIn: () -> Unit = {},
    onBack: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onEditPaymentApps: () -> Unit = {},
    onEditName: (String) -> Unit = {},
    onSendFeedback: () -> Unit = {},
    onPrivacy: () -> Unit = {},
    onTerms: () -> Unit = {},
    notifications: NotificationPrefs = NotificationPrefs(),
    notificationsBlocked: Boolean = false,
    onNotificationsChange: (NotificationPrefs) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onDeleteAccount: () -> Unit = {},
    deleteAccountError: String? = null,
) {
    val c = EvenlyTheme.colors
    var analytics by remember { mutableStateOf(true) }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(displayName) { mutableStateOf(displayName) }
    var confirmDelete by remember { mutableStateOf(false) }

    // A root tab of MainShell (the shell owns the status-bar scrim), but no bottom nav of its own —
    // matching Home, the only way back is this top-bar back button, not a persistent nav bar.
    Column(Modifier.fillMaxSize().background(c.page)) {
        EvTopBar(title = "Settings", center = true, navIcon = { EvIconButton(EvIcons.Back, onClick = onBack) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Account header card ────────────────────────────
            if (isSignedIn) {
                EvCard(padded = true) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EvAvatar(displayName, me = true, size = AvatarSize.Lg)
                        Column(Modifier.weight(1f)) {
                            if (editingName) {
                                EvTextField(nameDraft, { nameDraft = it }, placeholder = "Your name")
                            } else {
                                Text(displayName, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(email, color = c.ink2, fontSize = 12.sp)
                            }
                        }
                        if (editingName) {
                            EvIconButton(EvIcons.Check, { onEditName(nameDraft.trim()); editingName = false }, size = 20.dp, tint = c.blueText)
                        } else {
                            EvIconButton(EvIcons.Edit, { nameDraft = displayName; editingName = true }, size = 20.dp, tint = c.ink2)
                        }
                    }
                }
            } else {
                EvCard(padded = true, modifier = Modifier.clickable(onClick = onSignIn)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.height(56.dp).width(56.dp).background(c.surface, shape = CircleShape)
                                .border(1.dp, c.border, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            EvIcon(EvIcons.User, size = 24.dp, tint = c.ink2)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Not signed in", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Tap to sign in", color = c.ink2, fontSize = 12.sp)
                        }
                        EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.ink3)
                    }
                }
            }

            // ── Preferences ────────────────────────────────────
            ProfileGroup("Preferences") {
                ProfileRow(icon = EvIcons.Globe, label = "Base currency", value = baseCurrency)
                // Nudge the app's signature feature: an unset handle means peers can only "mark as
                // paid" instead of paying you through your own app. Surface it as an action, not a dead value.
                ProfileRow(
                    icon = EvIcons.Wallet,
                    label = "Payment apps",
                    value = if (paymentAppsSet) paymentAppsSummary else "Add one",
                    valueColor = if (paymentAppsSet) null else c.blueText,
                    hint = if (paymentAppsSet) null else "So friends can pay you back",
                    last = true,
                    onClick = onEditPaymentApps,
                )
            }

            // ── Notifications ──────────────────────────────────
            ProfileGroup("Notifications") {
                // Only shown once the OS permission has actually been asked and refused — explains why
                // the toggles below won't turn on (a constraint the user needs to act on, not marketing).
                if (notificationsBlocked) {
                    Text(
                        "Blocked in system settings",
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                        color = c.ink3,
                        fontSize = 12.sp,
                    )
                }
                NotifRow("Someone adds an expense", notifications.newExpenses) { onNotificationsChange(notifications.copy(newExpenses = it)) }
                NotifRow("Someone pays you", notifications.payments, last = true) { onNotificationsChange(notifications.copy(payments = it)) }
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
                    EvIcon(EvIcons.Chart, size = 20.dp, tint = c.ink2)
                    Column(Modifier.weight(1f)) {
                        Text("Anonymous analytics", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Never includes expense details", color = c.ink2, fontSize = 12.sp)
                    }
                    EvToggle(analytics, { analytics = it })
                }
            }

            // ── Appearance ─────────────────────────────────────
            ProfileGroup("Appearance") {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    EvSegmented(
                        options = listOf("System", "Light", "Dark"),
                        selected = themeMode.name,
                        onSelect = { onThemeModeChange(ThemeMode.fromName(it)) },
                    )
                }
            }

            // ── Support ────────────────────────────────────────
            ProfileGroup("Support") {
                ProfileRow(icon = EvIcons.Comment, label = "Send feedback", onClick = onSendFeedback)
                ProfileRow(icon = EvIcons.Lock, label = "Privacy policy", onClick = onPrivacy)
                ProfileRow(icon = EvIcons.Info, label = "Terms of service", last = true, onClick = onTerms)
            }

            // ── Account ────────────────────────────────────────
            ProfileGroup("Account") {
                ProfileRow(icon = EvIcons.Back, label = "Sign out", onClick = onSignOut)
                ProfileRow(icon = EvIcons.Trash, label = "Delete account", danger = true, last = true, showChevron = false, onClick = { confirmDelete = true })
            }

            Text(
                "Evenly v2.4.0",
                modifier = Modifier.fillMaxWidth(),
                color = c.ink2,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDelete) {
        EvModalScaffold(onDismiss = { confirmDelete = false }) {
            Text("Delete account?", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "You'll be signed out now, and your account is fully removed in 30 days. Sign back in " +
                    "before then to cancel. Your name, email, and photo are deleted. Expenses and " +
                    "payments you were part of stay in your groups' history so balances stay accurate " +
                    "for everyone else, shown as \"Deleted user.\"",
                color = c.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            deleteAccountError?.let {
                Text(it, color = c.danger, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EvButton("Cancel", { confirmDelete = false }, variant = ButtonVariant.Secondary, modifier = Modifier.weight(1f))
                EvButton("Delete", onDeleteAccount, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The JSX `Group` helper — a section label over a clipped `.sc-card` of rows. */
@Composable
private fun ProfileGroup(label: String, content: @Composable () -> Unit) {
    Column {
        EvSectionLabel(label)
        EvCard { content() }
    }
}

/** The JSX `SetRow` — icon + label + optional value + chevron (or red, chevron-less for danger). */
@Composable
private fun ProfileRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    value: String? = null,
    valueColor: androidx.compose.ui.graphics.Color? = null,
    hint: String? = null,
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
        Column(Modifier.weight(1f)) {
            Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start)
            hint?.let { Text(it, color = c.ink3, fontSize = 12.sp) }
        }
        value?.let { Text(it, color = valueColor ?: c.ink2, fontSize = 14.sp) }
        if (!danger && showChevron) EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.ink3)
    }
}

/** A notification toggle row (`.sc-row`), controlled by the caller so the value persists (F7). */
@Composable
private fun NotifRow(label: String, checked: Boolean, last: Boolean = false, onCheckedChange: (Boolean) -> Unit) {
    val c = EvenlyTheme.colors
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
        EvToggle(checked, onCheckedChange)
    }
}

@Preview
@Composable
private fun ProfilePreview() {
    EvenlyTheme { ProfileScreen() }
}
