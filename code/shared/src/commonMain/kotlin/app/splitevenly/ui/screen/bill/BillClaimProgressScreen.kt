package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.ClaimProgressState
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvProgress
import app.splitevenly.ui.components.EvSectionLabel
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/** One person on the bill: what they hold so far, and whether they have marked themselves done. */
data class ClaimProgressPersonUi(
    val userId: String,
    val name: String,
    val isMe: Boolean,
    val done: Boolean,
    val itemCount: Int,
    val amountSubunits: Long,
    /** How far they got, used only while they hold nothing yet. */
    val state: ClaimProgressState,
)

/** A line nobody fully holds yet, with the money on it that is still unassigned. */
data class ProgressLineUi(
    val itemId: String,
    val label: String,
    val unassignedSubunits: Long,
    val detail: String,
)

data class BillClaimProgressState(
    val billTitle: String,
    val currency: String,
    val people: List<ClaimProgressPersonUi>,
    val remaining: List<ProgressLineUi>,
    val notAssignedSubunits: Long,
    /** Milliseconds since the last successful poll, for the "updated N ago" honesty line. */
    val lastUpdatedAgoMs: Long?,
) {
    val doneCount: Int get() = people.count { it.done }

    /** People with nothing on the bill yet: the ones a leftover split would go to. */
    val holdingNothing: List<ClaimProgressPersonUi> get() = people.filter { it.itemCount == 0 }
}

/**
 * "Claim progress" (frame 6B): the payer's watch screen while people pick in their browsers
 * (WEB_CLAIM_SPEC.md §3.9.2). Every participant is listed with their own done state, and every line
 * that still needs someone is named with an `Assign it` that opens it on this phone, so a bill where
 * someone went home stays finishable. People done and items assigned are separate counts on purpose.
 *
 * **Polled in the app only, never on web**: the realtime doorbell is membership-RLS-scoped and a guest
 * browser cannot subscribe to it. The leftover choices name both resolutions instead of splitting
 * silently, because an unclaimed line is somebody's dinner. DI-free.
 */
@Composable
fun BillClaimProgressScreen(
    state: BillClaimProgressState,
    onBack: () -> Unit = {},
    onAssignItem: (itemId: String) -> Unit = {},
    onShowQr: () -> Unit = {},
    onReview: () -> Unit = {},
    /** Give the leftover to the people who hold nothing yet. */
    onSplitBetweenOutstanding: () -> Unit = {},
    /** Give the leftover to everyone on the bill. */
    onSplitAcrossEveryone: () -> Unit = {},
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim()
        EvTopBar("Claim progress", subtitle = state.billTitle, navIcon = { EvIconButton(EvIcons.Back, onBack) }, showDivider = false)
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
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.doneCount} of ${plural(state.people.size, "person", "people")} finished",
                        color = c.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (state.remaining.isEmpty()) "Every item assigned" else "${plural(state.remaining.size, "item")} left",
                        color = c.ink3,
                        fontSize = 12.5.sp,
                    )
                }
                EvProgress(if (state.people.isEmpty()) 0f else state.doneCount.toFloat() / state.people.size)
                Text(freshness(state.lastUpdatedAgoMs), color = c.ink3, fontSize = 12.sp)
            }

            Column {
                EvSectionLabel("People")
                EvCard {
                    state.people.forEachIndexed { i, p -> PersonRow(p, state.currency, first = i == 0) }
                }
            }

            if (state.remaining.isNotEmpty()) {
                Column {
                    EvSectionLabel("Still unclaimed")
                    EvCard {
                        state.remaining.forEachIndexed { i, line -> RemainingRow(line, state.currency, i == 0, onAssignItem) }
                        Row(Modifier.fillMaxWidth().topHairline(c.border).padding(horizontal = 12.dp, vertical = 11.dp)) {
                            Text("Not assigned yet", color = c.ink2, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                            Text(
                                moneySubunits(state.notAssignedSubunits, state.currency),
                                color = c.ink,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = EvenlyTheme.monoFamily,
                            )
                        }
                    }
                }
                LeftoverChoices(state.holdingNothing.size, onSplitBetweenOutstanding, onSplitAcrossEveryone)
            }

            Text(
                "Show the QR code again",
                color = c.blueText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onShowQr)
                        .padding(vertical = 10.dp),
            )
        }
        Box(Modifier.fillMaxWidth().topHairline(c.border).padding(16.dp)) {
            EvButton("Review assignments", onReview)
        }
    }
}

/** "updated just now" is only honest for a few seconds; after that say how stale it actually is. */
private fun freshness(agoMs: Long?): String =
    when {
        agoMs == null -> "Waiting for an update"
        agoMs < 10_000 -> "Updated just now"
        agoMs < 60_000 -> "Updated ${agoMs / 1000}s ago"
        agoMs < 3_600_000 -> "Updated ${agoMs / 60_000}m ago"
        else -> "Updated over an hour ago"
    }

/**
 * "Hasn't opened the link" is only true of someone without the app, so an app member who hasn't got
 * round to it gets different wording: re-sending a link to someone who was never going to use one is how
 * a nudge becomes noise.
 */
private fun waitingLine(state: ClaimProgressState): String =
    when (state) {
        ClaimProgressState.CLAIMED -> "Still choosing"
        ClaimProgressState.OPENED_NOTHING_CLAIMED -> "Opened the bill, still choosing"
        ClaimProgressState.NOT_OPENED -> "Hasn't opened the link"
        ClaimProgressState.APP_MEMBER_NOT_CLAIMED -> "Hasn't picked in the app yet"
    }

@Composable
private fun PersonRow(
    p: ClaimProgressPersonUi,
    currency: String,
    first: Boolean,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().then(if (first) Modifier else Modifier.topHairline(c.border)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EvAvatar(p.name, me = p.isMe, size = AvatarSize.Sm)
        Column(Modifier.weight(1f)) {
            Text(if (p.isMe) "You" else p.name, color = c.ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (p.itemCount >
                    0
                ) {
                    "${plural(p.itemCount, "item")} · ${moneySubunits(p.amountSubunits, currency)}"
                } else {
                    waitingLine(p.state)
                },
                color = c.ink2,
                fontSize = 12.sp,
            )
        }
        val (bg, fg) = if (p.done) c.settledTint to c.settled else c.warningTint to c.warning
        Text(
            if (p.done) "DONE" else "WAITING",
            color = fg,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun RemainingRow(
    line: ProgressLineUi,
    currency: String,
    first: Boolean,
    onAssignItem: (String) -> Unit,
) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().then(if (first) Modifier else Modifier.topHairline(c.border)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    line.label,
                    color = c.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    "  ${moneySubunits(line.unassignedSubunits, currency)}",
                    color = c.ink2,
                    fontSize = 13.sp,
                    fontFamily = EvenlyTheme.monoFamily,
                )
            }
            Text(line.detail, color = c.ink3, fontSize = 12.sp)
        }
        Text(
            "Assign it",
            color = c.blueText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .border(1.dp, c.blue, RoundedCornerShape(99.dp))
                    .clickable { onAssignItem(line.itemId) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** The two ways to close out what nobody picked (spec E17). Offered, never applied automatically. */
@Composable
private fun LeftoverChoices(
    holdingNothing: Int,
    onSplitBetweenOutstanding: () -> Unit,
    onSplitAcrossEveryone: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("If no one picks these, you can split what's left:", color = c.ink2, fontSize = 12.5.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (holdingNothing > 0) {
                LeftoverChip(
                    if (holdingNothing ==
                        1
                    ) {
                        "Give it to the one left"
                    } else {
                        "Split between the $holdingNothing left"
                    },
                    onSplitBetweenOutstanding,
                )
            }
            LeftoverChip("Split across everyone", onSplitAcrossEveryone)
        }
    }
}

@Composable
private fun LeftoverChip(
    label: String,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Text(
        label,
        color = c.ink,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, c.border, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Preview
@Composable
private fun BillClaimProgressPreview() {
    EvenlyTheme {
        BillClaimProgressScreen(
            BillClaimProgressState(
                billTitle = "Friday dinner",
                currency = "USD",
                people =
                    listOf(
                        ClaimProgressPersonUi("u1", "You", true, true, 1, 2400, ClaimProgressState.CLAIMED),
                        ClaimProgressPersonUi("u2", "Purity", false, true, 1, 1200, ClaimProgressState.CLAIMED),
                        ClaimProgressPersonUi("u3", "Maya", false, false, 0, 0, ClaimProgressState.OPENED_NOTHING_CLAIMED),
                    ),
                remaining = listOf(ProgressLineUi("i3", "Butter Chicken", 2400, "No one has picked this yet")),
                notAssignedSubunits = 2400,
                lastUpdatedAgoMs = 3000,
            ),
        )
    }
}
