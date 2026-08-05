package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.ClaimProgressState
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvProgress
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/** One person on the bill and how far they've got. */
data class ClaimProgressPersonUi(
    val userId: String,
    val name: String,
    val state: ClaimProgressState,
)

data class BillClaimProgressState(
    val billTitle: String,
    val currency: String,
    val billTotalSubunits: Long,
    val claimedSubunits: Long,
    /** Everyone who still owes a claim. People who have claimed are deliberately not listed. */
    val outstanding: List<ClaimProgressPersonUi>,
    val unclaimedSubunits: Long,
    val unclaimedItemCount: Int,
    /** Milliseconds since the last successful poll, for the "updated N ago" honesty line. */
    val lastUpdatedAgoMs: Long?,
) {
    val claimedFraction: Float
        get() = if (billTotalSubunits <= 0L) 0f else (claimedSubunits.toDouble() / billTotalSubunits).toFloat()

    val hasRemainder: Boolean get() = unclaimedSubunits > 0L
}

/**
 * "Who's still to claim" — the payer's watch screen (WEB_CLAIM_SPEC.md §3.9.2).
 *
 * **Polled in the app only, never on web.** The realtime doorbell is membership-RLS-scoped and an anon
 * browser cannot subscribe to it, so the guest polls; here the payer does too, because this is the one
 * screen where "has anything changed in the last five seconds" is the entire question being asked.
 *
 * It also closes the loop the whole feature depends on: a bill where three people went home must still
 * be finishable. That is what the remainder block is for, and why it names both resolutions instead of
 * quietly splitting evenly. DI-free.
 */
@Composable
fun BillClaimProgressScreen(
    state: BillClaimProgressState,
    onBack: () -> Unit = {},
    /** Give the leftover to the people who never claimed. */
    onSplitBetweenOutstanding: () -> Unit = {},
    /** Give the leftover to everyone on the bill. */
    onSplitAcrossEveryone: () -> Unit = {},
    onShareLink: () -> Unit = {},
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
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
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Who's still to claim", color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                EvProgress(state.claimedFraction)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontFamily = EvenlyTheme.monoFamily, fontWeight = FontWeight.Bold, color = c.ink)) {
                                append(moneySubunits(state.claimedSubunits, state.currency))
                            }
                            append(" of ")
                            withStyle(SpanStyle(fontFamily = EvenlyTheme.monoFamily)) {
                                append(moneySubunits(state.billTotalSubunits, state.currency))
                            }
                        },
                        color = c.ink2,
                        fontSize = 12.5.sp,
                    )
                    Text(freshness(state.lastUpdatedAgoMs), color = c.ink3, fontSize = 12.5.sp)
                }
            }

            if (state.outstanding.isEmpty()) {
                EverybodyClaimed()
            } else {
                EvCard(padded = true) {
                    state.outstanding.forEachIndexed { index, person ->
                        PersonRow(person, first = index == 0)
                    }
                }
            }

            if (state.hasRemainder) {
                RemainderNote(
                    state = state,
                    onSplitBetweenOutstanding = onSplitBetweenOutstanding,
                    onSplitAcrossEveryone = onSplitAcrossEveryone,
                )
            }

            EvButton("Share the link again", onShareLink, variant = ButtonVariant.Secondary, leadingIcon = EvIcons.Share, small = true)
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** "updated just now" is only honest for a few seconds; after that say how stale it actually is. */
private fun freshness(agoMs: Long?): String = when {
    agoMs == null -> "waiting for an update"
    agoMs < 10_000 -> "updated just now"
    agoMs < 60_000 -> "updated ${agoMs / 1000}s ago"
    agoMs < 3_600_000 -> "updated ${agoMs / 60_000}m ago"
    else -> "updated over an hour ago"
}

@Composable
private fun PersonRow(person: ClaimProgressPersonUi, first: Boolean) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (first) Modifier else Modifier.topHairline(c.border))
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EvAvatar(person.name, size = AvatarSize.Sm)
        Column(Modifier.fillMaxWidth()) {
            Text(person.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stateLine(person.state), color = c.ink2, fontSize = 12.5.sp)
        }
    }
}

/**
 * The honest wording per state. "Hasn't opened the link" is only true of someone without the app, so an
 * app member who simply hasn't got round to it gets a different line — telling the payer to re-send a
 * link to somebody who was never going to use one is how a nudge becomes noise.
 */
private fun stateLine(state: ClaimProgressState): String = when (state) {
    ClaimProgressState.CLAIMED -> "Claimed"
    ClaimProgressState.OPENED_NOTHING_CLAIMED -> "Opened it, claimed nothing"
    ClaimProgressState.NOT_OPENED -> "Hasn't opened the link"
    ClaimProgressState.APP_MEMBER_NOT_CLAIMED -> "Hasn't claimed in the app yet"
}

@Composable
private fun EverybodyClaimed() {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.blueTint)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EvIcon(EvIcons.CheckCircle, size = 16.dp, tint = c.blueText)
        Text("Everyone has claimed.", color = c.blueText, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The leftover, and the two ways out of it (spec E17). Both are offered rather than one being applied
 * automatically: an unclaimed line is somebody's dinner, and guessing whose is how a bill quietly
 * charges the wrong person.
 */
@Composable
private fun RemainderNote(
    state: BillClaimProgressState,
    onSplitBetweenOutstanding: () -> Unit,
    onSplitAcrossEveryone: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val outstandingCount = state.outstanding.size
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.warningTint)
            .border(1.dp, c.warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontFamily = EvenlyTheme.monoFamily, fontWeight = FontWeight.Bold, color = c.ink)) {
                    append(moneySubunits(state.unclaimedSubunits, state.currency))
                }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.ink)) { append(" is unclaimed") }
                append(" across ${state.unclaimedItemCount} ${if (state.unclaimedItemCount == 1) "item" else "items"}. ")
                append("If they never claim, you decide what happens to it.")
            },
            color = c.ink2,
            fontSize = 12.5.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (outstandingCount > 0) {
                RemainderChip(
                    if (outstandingCount == 1) "Give it to the one left" else "Split it between the $outstandingCount left",
                    onSplitBetweenOutstanding,
                )
            }
            RemainderChip("Split it across everyone", onSplitAcrossEveryone)
        }
    }
}

@Composable
private fun RemainderChip(label: String, onClick: () -> Unit) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(c.page)
            .border(1.dp, c.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
