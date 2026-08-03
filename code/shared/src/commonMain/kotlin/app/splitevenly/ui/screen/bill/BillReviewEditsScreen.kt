package app.splitevenly.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.domain.expense.PendingEditDecision
import app.splitevenly.domain.expense.PendingEditKind
import app.splitevenly.ui.components.BannerVariant
import app.splitevenly.ui.components.EvBanner
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvIconButton
import app.splitevenly.ui.components.EvTopBar
import app.splitevenly.ui.components.StatusBarScrim
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.theme.EvenlyTheme

/** One proposal, as the payer sees it. Copy is assembled in the screen so this stays previewable. */
data class ReviewEditUi(
    val id: String,
    val proposerName: String,
    val kind: PendingEditKind,
    val itemLabel: String,
    val previousLabel: String? = null,
    val previousQuantity: Int? = null,
    val previousUnitPriceSubunits: Long? = null,
    val proposedQuantity: Int? = null,
    val proposedUnitPriceSubunits: Long? = null,
    /** Signed change to the bill total if approved. */
    val deltaSubunits: Long = 0L,
    val decision: PendingEditDecision? = null,
)

data class ReviewEditsState(
    val billTitle: String,
    val currency: String,
    val edits: List<ReviewEditUi>,
    /** The bill's total as it stands now, before anything here is approved. */
    val currentTotalSubunits: Long,
) {
    val pending: List<ReviewEditUi> get() = edits.filter { it.decision == null }
    val decided: List<ReviewEditUi> get() = edits.filter { it.decision != null }

    /** What the bill would come to if every waiting change were approved. */
    val totalIfAllApprovedSubunits: Long get() = currentTotalSubunits + pending.sumOf { it.deltaSubunits }
}

/**
 * "N changes to review" — the payer decides each web guest's proposed menu change, one card at a time
 * (WEB_CLAIM_SPEC.md §3.9.1).
 *
 * **There is no "approve all".** A payer who clears three cards with one tap has reviewed none of them,
 * and each card is somebody's money. The asymmetry with joining a claim (which applies instantly) is
 * deliberate and documented in §2.7: joining moves two people's money with both of them at the table;
 * editing a line moves the bill total and therefore everyone's.
 *
 * The bill keeps its current amounts until a card is decided, so an unreviewed edit can never quietly
 * move anyone's balance. DI-free.
 */
@Composable
fun BillReviewEditsScreen(
    state: ReviewEditsState,
    onBack: () -> Unit = {},
    onApprove: (editId: String) -> Unit = {},
    onReject: (editId: String) -> Unit = {},
    /** Set when a decision could not be saved. Shown rather than swallowed: the tap looked like it worked. */
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val c = EvenlyTheme.colors
    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim()
        EvTopBar(
            title = state.billTitle,
            navIcon = { EvIconButton(EvIcons.Back, onBack) },
            showDivider = false,
        )
        notice?.let {
            Row(Modifier.fillMaxWidth().clickable { onDismissNotice() }) {
                EvBanner(it, variant = BannerVariant.Amber, leadingIcon = EvIcons.WifiOff)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            item {
                Column(Modifier.padding(top = 4.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        headline(state.pending.size),
                        color = c.ink,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.pending.isEmpty()) "Nothing is waiting on you."
                        else "People edited the bill after it was scanned. Approve each one.",
                        color = c.ink2,
                        fontSize = 13.5.sp,
                    )
                }
            }

            items(state.pending, key = { it.id }) { edit ->
                EditCard(edit, state.currency, onApprove = { onApprove(edit.id) }, onReject = { onReject(edit.id) })
            }

            if (state.pending.isNotEmpty()) {
                item { WaitingNote(state) }
            }

            if (state.decided.isNotEmpty()) {
                item {
                    Text(
                        "Already decided",
                        color = c.ink3,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                items(state.decided, key = { "d-${it.id}" }) { edit -> DecidedCard(edit) }
            }
        }
    }
}

private fun headline(pendingCount: Int): String = when (pendingCount) {
    0 -> "No changes to review"
    1 -> "1 change to review"
    else -> "$pendingCount changes to review"
}

/** Who did what, in one line: "Purity added Mango sticky rice". The item name carries the blue. */
@Composable
private fun EditCard(edit: ReviewEditUi, currency: String, onApprove: () -> Unit, onReject: () -> Unit) {
    val c = EvenlyTheme.colors
    EvCard(padded = true) {
        Text(
            buildAnnotatedString {
                append("${edit.proposerName} ${verb(edit.kind)} ")
                withStyle(SpanStyle(color = c.blueText, fontWeight = FontWeight.SemiBold)) {
                    append(edit.itemLabel)
                }
            },
            color = c.ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        DetailLine(edit, currency)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecisionChip("Approve", approve = true, onClick = onApprove)
            DecisionChip("Reject", approve = false, onClick = onReject)
        }
    }
}

/** Before and after, with the old value struck through so the change is readable without arithmetic. */
@Composable
private fun DetailLine(edit: ReviewEditUi, currency: String) {
    val c = EvenlyTheme.colors
    val mono = EvenlyTheme.monoFamily
    val text = buildAnnotatedString {
        when (edit.kind) {
            PendingEditKind.ADD -> {
                append("Wasn't on the receipt")
                append("  ·  ")
                withStyle(SpanStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold)) {
                    append("${edit.proposedQuantity ?: 1} × ${moneySubunits(edit.proposedUnitPriceSubunits ?: 0L, currency)}")
                }
            }
            PendingEditKind.REPRICE -> {
                append("Price each")
                append("  ·  ")
                withStyle(SpanStyle(fontFamily = mono, color = c.ink3, textDecoration = TextDecoration.LineThrough)) {
                    append(moneySubunits(edit.previousUnitPriceSubunits ?: 0L, currency))
                }
                append("  →  ")
                withStyle(SpanStyle(fontFamily = mono, fontWeight = FontWeight.Bold)) {
                    append(moneySubunits(edit.proposedUnitPriceSubunits ?: 0L, currency))
                }
            }
            PendingEditKind.REQUANTITY -> {
                append("Quantity")
                append("  ·  ")
                withStyle(SpanStyle(fontFamily = mono, color = c.ink3, textDecoration = TextDecoration.LineThrough)) {
                    append("${edit.previousQuantity ?: 1}")
                }
                append("  →  ")
                withStyle(SpanStyle(fontFamily = mono, fontWeight = FontWeight.Bold)) {
                    append("${edit.proposedQuantity ?: 1}")
                }
            }
            PendingEditKind.RELABEL -> {
                append("Name")
                append("  ·  ")
                withStyle(SpanStyle(color = c.ink3, textDecoration = TextDecoration.LineThrough)) {
                    append(edit.previousLabel.orEmpty())
                }
                append("  →  ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(edit.itemLabel) }
            }
            PendingEditKind.REMOVE -> {
                append("Takes it off the bill")
                append("  ·  ")
                withStyle(SpanStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold)) {
                    append(moneySubunits(edit.previousLineTotal(), currency))
                }
            }
        }
    }
    Text(text, color = c.ink2, fontSize = 12.5.sp)
}

private fun ReviewEditUi.previousLineTotal(): Long =
    (previousUnitPriceSubunits ?: 0L) * (previousQuantity ?: 1)

private fun verb(kind: PendingEditKind): String = when (kind) {
    PendingEditKind.ADD -> "added"
    PendingEditKind.REMOVE -> "removed"
    PendingEditKind.RELABEL -> "renamed"
    PendingEditKind.REPRICE, PendingEditKind.REQUANTITY -> "changed"
}

/** Both verdicts are the same weight on purpose: neither is the recommended one. */
@Composable
private fun DecisionChip(label: String, approve: Boolean, onClick: () -> Unit) {
    val c = EvenlyTheme.colors
    val fg = if (approve) c.blueText else c.ink2
    val border = if (approve) c.blueTint2 else c.border
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (approve) c.blueTint else c.surface)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EvIcon(if (approve) EvIcons.Check else EvIcons.Close, size = 15.dp, tint = fg)
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** What the payer has already settled, kept on screen so a mis-tap is visible rather than vanishing. */
@Composable
private fun DecidedCard(edit: ReviewEditUi) {
    val c = EvenlyTheme.colors
    val approved = edit.decision == PendingEditDecision.APPROVED
    EvCard(fill = true, bordered = true, padded = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EvIcon(if (approved) EvIcons.CheckCircle else EvIcons.Close, size = 16.dp, tint = c.ink3)
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "${edit.proposerName} ${verb(edit.kind)} ${edit.itemLabel}",
                    color = c.ink2,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (approved) "Approved. It's on the bill." else "Rejected. The bill didn't change.",
                    color = c.ink3,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** The one place the arithmetic is spelled out, so nobody has to add up card deltas in their head. */
@Composable
private fun WaitingNote(state: ReviewEditsState) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.warningTint)
            .border(1.dp, c.warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EvIcon(EvIcons.Info, size = 16.dp, tint = c.warning, modifier = Modifier.padding(top = 2.dp))
        Text(
            buildAnnotatedString {
                append("The bill keeps its current amounts until you decide. Approving everything here takes the total to ")
                withStyle(SpanStyle(fontFamily = EvenlyTheme.monoFamily, fontWeight = FontWeight.Bold)) {
                    append(moneySubunits(state.totalIfAllApprovedSubunits, state.currency))
                }
                append(".")
            },
            color = c.ink2,
            fontSize = 12.5.sp,
        )
    }
}
