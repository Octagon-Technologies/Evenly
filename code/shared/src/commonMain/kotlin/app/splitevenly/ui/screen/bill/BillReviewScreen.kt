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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.ItemStatus
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvSectionLabel
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/** One person's derived total on the review, with a plain-words list of what makes it up. */
data class ReviewPersonUi(
    val userId: String,
    val name: String,
    val isMe: Boolean,
    val amountSubunits: Long,
    val itemsLine: String,
    val done: Boolean,
)

/** An unresolved line and the money on it that nobody holds yet. */
data class UnresolvedLineUi(
    val item: ClaimItemUi,
    val unassignedSubunits: Long,
)

data class BillReviewState(
    val title: String,
    val currency: String,
    val totalSubunits: Long,
    /** Every line, receipt order. */
    val items: List<ClaimItemUi>,
    val unresolved: List<UnresolvedLineUi>,
    val people: List<ReviewPersonUi>,
    /** Bill total minus everyone's derived total, so the visible rows always add up to the bill. */
    val notAssignedSubunits: Long,
    val resolvedCount: Int,
) {
    val complete: Boolean get() = items.isNotEmpty() && unresolved.isEmpty()
    private val others: List<ReviewPersonUi> get() = people.filterNot { it.isMe }
    val othersNotDone: List<ReviewPersonUi> get() = others.filterNot { it.done }

    /** Someone else has marked done themselves, i.e. people are picking on their own devices. */
    val othersArePicking: Boolean get() = others.any { it.done }
}

/**
 * "Review assignments" (frames 7, 7A, 7B). Opens even while lines are unresolved: then it is a **partial
 * review** that shows the remaining work once (one card, each line with its own Assign), keeps a
 * `Not assigned` row so no one mistakes the visible totals for the bill, and offers two exits that both
 * leave the page. Only when every line is RESOLVED does it show the success hero and `Finish splitting`.
 *
 * "Everyone has picked" needs every *other* participant to have marked done; the viewer is the one
 * finishing here, so their own marker is what Finish writes. Otherwise it says what is actually true:
 * the items are assigned. DI-free.
 */
@Composable
fun BillReviewScreen(
    state: BillReviewState?,
    onBack: () -> Unit = {},
    onAssign: (itemId: String?) -> Unit = {},
    onPickMoreLater: () -> Unit = {},
    onPickedAllMine: () -> Unit = {},
    onFinish: () -> Unit = {},
    busy: Boolean = false,
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim()
        EvTopBar(
            title = "Review assignments",
            subtitle = state?.let { "${it.title} · ${moneySubunits(it.totalSubunits, it.currency)}" },
            navIcon = { EvIconButton(EvIcons.Back, onBack) },
            showDivider = false,
        )
        notice?.let {
            Row(Modifier.fillMaxWidth().clickable { onDismissNotice() }) {
                EvBanner(it, variant = BannerVariant.Amber, leadingIcon = EvIcons.Alert)
            }
        }
        if (state == null) {
            BillAssignSkeleton()
            return@Column
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.complete) {
                CompleteHero(state)
                if (state.othersArePicking) {
                    val picked = state.people.count { it.done || it.isMe }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, c.border, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text("People finished picking", color = c.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(
                            "$picked of ${state.people.size}",
                            color = c.ink,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = EvenlyTheme.monoFamily,
                        )
                    }
                }
                TotalsCard("Totals", state, onAssign)
                AssignmentsList(state.items, onAssign)
                Text(
                    "Assignments are already saved.",
                    color = c.ink3,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                AssignProgressHeader(state.resolvedCount, state.items.size, noun = "items assigned")
                StillNeedsSomeone(state, onAssign)
                TotalsCard("Who owes what so far", state, onAssign)
                var showAll by remember { mutableStateOf(false) }
                if (showAll) {
                    AssignmentsList(state.items, onAssign)
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .border(1.dp, c.border, RoundedCornerShape(11.dp))
                            .clickable { showAll = true }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "See all ${state.items.size} items in receipt order",
                            color = c.blueText,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        EvIcon(EvIcons.ChevR, size = 15.dp, tint = c.blueText)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().topHairline(c.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.complete) {
                EvButton(if (busy) "Saving…" else "Finish splitting", onFinish, enabled = !busy)
            } else {
                Column {
                    Text("Done here? Your picks are saved.", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Others in this group can pick the rest later.", color = c.ink2, fontSize = 12.5.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EvButton(
                        "I'll pick more later",
                        onPickMoreLater,
                        modifier = Modifier.weight(1f),
                        variant = ButtonVariant.Tonal,
                        enabled = !busy,
                        fillMaxWidth = false,
                    )
                    EvButton(
                        if (busy) "Saving…" else "I've picked all mine",
                        onPickedAllMine,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        fillMaxWidth = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompleteHero(state: BillReviewState) {
    val c = EvenlyTheme.colors
    val waiting = state.othersNotDone
    val everyone = state.othersArePicking && waiting.isEmpty()
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(62.dp).clip(CircleShape).background(c.settledTint), contentAlignment = Alignment.Center) {
            EvIcon(EvIcons.Check, size = 30.dp, tint = c.settled)
        }
        Text(
            if (everyone) "Everyone has picked" else "All items are assigned",
            color = c.ink,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            when {
                everyone -> {
                    if (state.items.size ==
                        1
                    ) {
                        "The item is assigned. The split is ready."
                    } else {
                        "All ${state.items.size} items are assigned. The split is ready."
                    }
                }

                !state.othersArePicking -> {
                    "Everyone's share is ready. You can still change an item before finishing."
                }

                waiting.size == 1 -> {
                    "The split is ready. ${waiting[0].name} has not marked done yet."
                }

                waiting.size == 2 -> {
                    "The split is ready. ${waiting[0].name} and ${waiting[1].name} have not marked done yet."
                }

                else -> {
                    "The split is ready. ${waiting.size} people have not marked done yet."
                }
            },
            color = c.ink2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp),
        )
    }
}

@Composable
private fun StillNeedsSomeone(
    state: BillReviewState,
    onAssign: (String?) -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.warningTint)
            .border(1.dp, c.warning.copy(alpha = 0.3f), shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 4.dp)) {
            Text(
                "Still needs someone",
                color = c.warning,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                moneySubunits(state.notAssignedSubunits, state.currency),
                color = c.warning,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = EvenlyTheme.monoFamily,
            )
        }
        state.unresolved.forEachIndexed { i, line ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (i == 0) Modifier else Modifier.topHairline(c.warning.copy(alpha = 0.25f)))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        line.item.label,
                        color = c.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val money = moneySubunits(line.unassignedSubunits, state.currency)
                    Text(
                        when {
                            line.item.status == ItemStatus.OVERCLAIMED -> "Too many servings assigned"
                            line.item.quantity > 1 -> "${line.item.leftQty} of ${line.item.quantity} servings left · $money"
                            else -> money
                        },
                        color = c.ink2,
                        fontSize = 12.sp,
                    )
                }
                Text(
                    "Assign",
                    color = c.blueText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(c.page)
                            .border(1.dp, c.blue, RoundedCornerShape(99.dp))
                            .clickable { onAssign(line.item.id) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TotalsCard(
    label: String,
    state: BillReviewState,
    onAssign: (String?) -> Unit,
) {
    val c = EvenlyTheme.colors
    Column {
        EvSectionLabel(label)
        EvCard {
            state.people.forEachIndexed { i, p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (i == 0) Modifier else Modifier.topHairline(c.border))
                        .clickable { onAssign(null) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    EvAvatar(p.name, me = p.isMe, size = AvatarSize.Sm)
                    Column(Modifier.weight(1f)) {
                        Text(if (p.isMe) "You" else p.name, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (p.itemsLine.isNotEmpty() && !state.complete) {
                            Text(p.itemsLine, color = c.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(
                        moneySubunits(p.amountSubunits, state.currency),
                        color = c.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = EvenlyTheme.monoFamily,
                    )
                }
            }
            if (state.notAssignedSubunits > 0) {
                Row(
                    Modifier.fillMaxWidth().topHairline(c.border).padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(AvatarSize.Sm.dp).clip(CircleShape).border(1.3.dp, c.ink3, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("?", color = c.ink3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    Text("Not assigned", color = c.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        moneySubunits(state.notAssignedSubunits, state.currency),
                        color = c.ink2,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = EvenlyTheme.monoFamily,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssignmentsList(
    items: List<ClaimItemUi>,
    onAssign: (String?) -> Unit,
) {
    val c = EvenlyTheme.colors
    Column {
        EvSectionLabel("Assignments")
        EvCard {
            items.forEachIndexed { i, item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (i == 0) Modifier else Modifier.topHairline(c.border))
                        .clickable { onAssign(item.id) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        item.label,
                        color = c.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        item.assigneeLine(),
                        color = if (item.status == ItemStatus.RESOLVED) c.ink2 else c.warning,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    EvIcon(EvIcons.ChevR, size = 14.dp, tint = c.ink3)
                }
            }
        }
    }
}

@Preview
@Composable
private fun PartialReviewPreview() {
    val items =
        listOf(
            ClaimItemUi(
                "a",
                "Spicy tuna rolls",
                1,
                1800,
                listOf(AssignRowUi("p", listOf("u1", "u2"), listOf("You", "Purity"), 1, 1800)),
                ItemStatus.RESOLVED,
                1,
            ),
            ClaimItemUi("b", "Garlic prawns", 1, 2900, emptyList(), ItemStatus.UNCLAIMED, 2),
        )
    EvenlyTheme {
        BillReviewScreen(
            BillReviewState(
                "Large dinner",
                "USD",
                4700,
                items,
                listOf(UnresolvedLineUi(items[1], 2900)),
                listOf(
                    ReviewPersonUi("u1", "You", true, 900, "half of Spicy tuna rolls", false),
                    ReviewPersonUi("u2", "Purity", false, 900, "half of Spicy tuna rolls", false),
                ),
                2900,
                1,
            ),
        )
    }
}
