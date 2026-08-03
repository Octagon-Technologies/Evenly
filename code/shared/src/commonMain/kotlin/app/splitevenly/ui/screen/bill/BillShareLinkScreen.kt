package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.theme.EvenlyTheme
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * What the payer is looking at. The three states that matter are all reachable and all have a way out:
 * no link yet, a live link this device can draw, and a live link whose token lives on another device.
 */
data class ShareBillLinkState(
    val billTitle: String,
    /** `split-evenly.app/b/<token>`, or null when this device does not hold the plaintext token. */
    val url: String?,
    val exists: Boolean,
    val revoked: Boolean,
    /** Whole hours left before the link stops accepting writes; null when there is no live link. */
    val hoursLeft: Long?,
    val busy: Boolean = false,
) {
    val live: Boolean get() = exists && !revoked && (hoursLeft ?: 0L) > 0L

    /** A live link created elsewhere: real, working, and unrenderable here. Not an error state. */
    val liveButTokenElsewhere: Boolean get() = live && url == null
}

/**
 * "Let everyone claim" — the QR the payer holds up at the table (WEB_CLAIM_SPEC.md §3.9.3).
 *
 * **The QR is primary and the group invite is tertiary, and they are two different links** (§2.9). This
 * one is per-expense, 72 hours, claim-only, and needs no account; the group invite is permanent and does.
 * Do not merge the two controls, and do not promote the invite: holding up a screen beats messaging
 * eleven people who are standing up to leave.
 *
 * The scope reassurance at the foot is not decoration. A guest is being handed a URL into someone's
 * finances, and the payer is the one who has to be comfortable passing it round. DI-free.
 */
@Composable
fun BillShareLinkScreen(
    state: ShareBillLinkState,
    onBack: () -> Unit = {},
    onCreate: () -> Unit = {},
    onShare: () -> Unit = {},
    onCopy: () -> Unit = {},
    onExtend: () -> Unit = {},
    onRevoke: () -> Unit = {},
    onInviteToGroup: () -> Unit = {},
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    var copied by remember(state.url) { mutableStateOf(false) }
    var confirmRevoke by remember(state.url) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim()
        EvTopBar(state.billTitle, navIcon = { EvIconButton(EvIcons.Back, onBack) }, showDivider = false)
        notice?.let {
            Row(Modifier.fillMaxWidth().clickable { onDismissNotice() }) {
                EvBanner(it, variant = BannerVariant.Amber, leadingIcon = EvIcons.WifiOff)
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Let everyone claim", color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Hold this up at the table. They pick what they had, no app needed.",
                    color = c.ink2,
                    fontSize = 13.5.sp,
                )
            }

            when {
                state.url != null && state.live -> {
                    QrPlate(state.url)
                    ExpiryLine(state.url, state.hoursLeft)
                }
                state.liveButTokenElsewhere -> TokenElsewhereNote()
                state.revoked -> DeadLinkNote("This link is off. Nobody can open the bill with it.")
                state.exists -> DeadLinkNote("This link has expired. Nobody can claim with it any more.")
                else -> NoLinkYetNote()
            }

            Spacer(Modifier.height(4.dp))

            // Every branch keeps a live primary action. A greyed-out control with no explanation is the
            // dead end AGENTS.md §7 forbids; here the way forward is always "make a link that works".
            if (state.url != null && state.live) {
                EvButton("Share the link instead", onShare, leadingIcon = EvIcons.Share, enabled = !state.busy)
                EvButton(
                    text = if (copied) "Link copied" else "Copy link",
                    onClick = { onCopy(); copied = true },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = if (copied) EvIcons.Check else EvIcons.Copy,
                    enabled = !state.busy,
                )
            } else {
                EvButton(
                    text = if (state.exists) "Make a new link" else "Create the link",
                    onClick = onCreate,
                    leadingIcon = EvIcons.Qr,
                    enabled = !state.busy,
                )
            }

            EvButton(
                "Invite them to the group on Evenly",
                onInviteToGroup,
                variant = ButtonVariant.Text,
                small = true,
                enabled = !state.busy,
            )

            ScopeNote()

            if (state.live) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EvButton(
                        "Give it 72 more hours",
                        onExtend,
                        variant = ButtonVariant.Text,
                        small = true,
                        fillMaxWidth = false,
                        modifier = Modifier.weight(1f),
                        enabled = !state.busy,
                    )
                    EvButton(
                        if (confirmRevoke) "Tap again to turn it off" else "Turn the link off",
                        onClick = { if (confirmRevoke) onRevoke() else confirmRevoke = true },
                        variant = ButtonVariant.Danger,
                        small = true,
                        fillMaxWidth = false,
                        modifier = Modifier.weight(1f),
                        enabled = !state.busy,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** White plate + dark modules so it scans in both themes, and so a phone camera sees real contrast. */
@Composable
private fun QrPlate(url: String) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
            Image(
                painter = rememberQrCodePainter(url),
                contentDescription = "QR code to claim this bill",
                modifier = Modifier.size(208.dp),
            )
        }
    }
}

@Composable
private fun ExpiryLine(url: String, hoursLeft: Long?) {
    val c = EvenlyTheme.colors
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = EvenlyTheme.monoFamily)) { append(url) }
            append("  ·  ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.ink)) {
                append(
                    when {
                        hoursLeft == null -> "open"
                        hoursLeft >= 2 -> "open for $hoursLeft hours"
                        hoursLeft == 1L -> "open for 1 more hour"
                        else -> "closing within the hour"
                    },
                )
            }
        },
        color = c.ink2,
        fontSize = 12.5.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The honest read of "the server has a live link, this phone has no token for it". */
@Composable
private fun TokenElsewhereNote() {
    Note(
        icon = EvIcons.Info,
        title = "The code is on the phone that made it",
        body = "This bill already has a link, but only the phone that created it can show the code. Make a new one to share it from here. The old one stops working.",
    )
}

@Composable
private fun DeadLinkNote(body: String) {
    Note(icon = EvIcons.Info, title = "No one can claim right now", body = body)
}

@Composable
private fun NoLinkYetNote() {
    Note(
        icon = EvIcons.Qr,
        title = "No link yet",
        body = "Create one and everyone at the table can claim what they had, without installing anything.",
    )
}

@Composable
private fun Note(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EvIcon(icon, size = 16.dp, tint = c.ink3, modifier = Modifier.padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = c.ink2, fontSize = 12.5.sp)
        }
    }
}

/** Spelled out because the payer is the one deciding whether to pass this round (spec §3.9.3). */
@Composable
private fun ScopeNote() {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.blueTint)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EvIcon(EvIcons.Lock, size = 15.dp, tint = c.blueText, modifier = Modifier.padding(top = 1.dp))
        Text(
            buildAnnotatedString {
                append("This link opens ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("this bill only") }
                append(". It can't see your balances, your other expenses, or anyone's history.")
            },
            color = c.blueText,
            fontSize = 12.5.sp,
        )
    }
}
