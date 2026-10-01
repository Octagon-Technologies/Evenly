package app.splitevenly.ui.screen.bill

import androidx.compose.ui.graphics.Brush
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
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
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
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * What the payer is looking at. The three states that matter are all reachable and all have a way out:
 * no link yet, a live link this device can draw, and a live link whose token lives on another device.
 */
data class ShareBillLinkState(
    val billTitle: String,
    /** `split-evenly.app/b/<token>`, or null until the route recovers the permanent token. */
    val url: String?,
    val exists: Boolean,
    val revoked: Boolean,
    val busy: Boolean = false,
    val itemCount: Int = 0,
) {
    val live: Boolean get() = exists && !revoked
}

/**
 * "Let everyone claim" — the QR the payer holds up at the table (WEB_CLAIM_SPEC.md §3.9.3).
 *
 * **The QR is primary and the group invite is tertiary, and they are two different links** (§2.9). This
 * one is per-expense, permanent, claim-only, and needs no account; the group invite is permanent and
 * account-required.
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
    onRevoke: () -> Unit = {},
    onInviteToGroup: () -> Unit = {},
    onSeeProgress: () -> Unit = {},
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    var confirmRevoke by remember(state.url) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim()
        EvTopBar(
            "Let everyone pick",
            subtitle = if (state.itemCount > 0) "${state.billTitle} · ${plural(state.itemCount, "item")}" else state.billTitle,
            navIcon = { EvIconButton(EvIcons.Back, onBack) },
            showDivider = false,
        )
        notice?.let {
            Row(Modifier.fillMaxWidth().clickable { onDismissNotice() }) {
                EvBanner(it, variant = BannerVariant.Amber, leadingIcon = EvIcons.WifiOff)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Scan to pick your items", color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Friends open the bill in their browser. No app or account needed.",
                    color = c.ink2,
                    fontSize = 13.5.sp,
                )
            }

            when {
                state.url != null && state.live -> {
                    QrPlate(state.url)
                    Text(
                        "QR ready",
                        color = c.credit,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                state.busy -> QrLoadingPlate()
                state.revoked -> DeadLinkNote("This link is off. Create a new QR to share this bill again.")
                else -> DeadLinkNote("Couldn't load the QR. Try again when you're connected.")
            }

            Spacer(Modifier.height(4.dp))

            // Every branch keeps a live primary action. A greyed-out control with no explanation is the
            // dead end AGENTS.md §7 forbids; creation starts automatically and this is the retry path.
            if (!state.busy) {
                if (state.url != null && state.live) {
                    EvButton("Share the link", onShare, leadingIcon = EvIcons.Share)
                } else {
                    EvButton(
                        text = if (state.revoked || state.exists) "Create a new QR" else "Try again",
                        onClick = onCreate,
                        leadingIcon = EvIcons.Qr,
                    )
                }
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
                EvButton(
                    if (confirmRevoke) "Tap again to turn it off" else "Turn the link off",
                    onClick = { if (confirmRevoke) onRevoke() else confirmRevoke = true },
                    variant = ButtonVariant.Danger,
                    small = true,
                    enabled = !state.busy,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        Box(Modifier.fillMaxWidth().topHairline(c.border).padding(16.dp)) {
            EvButton("See claim progress", onSeeProgress, variant = ButtonVariant.Tonal)
        }
    }
}

@Composable
private fun QrLoadingPlate() {
    val c = EvenlyTheme.colors
    val transition = rememberInfiniteTransition(label = "qr-loading")
    val offset by transition.animateFloat(
        initialValue = -280f,
        targetValue = 280f,
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)),
        label = "qr-shimmer-offset",
    )
    val shimmer = Brush.linearGradient(
        colors = listOf(c.surface, c.page, c.surface),
        start = Offset(offset, 0f),
        end = Offset(offset + 180f, 180f),
    )
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
            Box(Modifier.size(208.dp).clip(RoundedCornerShape(8.dp)).background(shimmer))
        }
    }
    Text(
        "Creating your bill QR…",
        color = c.blueText,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Text(
        "This may take a moment",
        color = c.ink3,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
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
private fun DeadLinkNote(body: String) {
    Note(icon = EvIcons.Info, title = "No one can claim right now", body = body)
}

@Composable
private fun Note(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
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
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.blueTint)
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
