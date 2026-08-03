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

/** One change, as the payer sees it. Copy is assembled in the screen so this stays previewable. */
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
    /** Signed effect this change had on the bill total. */
    val deltaSubunits: Long = 0L,
    val decision: PendingEditDecision? = null,
    /** Who took it back. Anyone on the bill can, so this is often not the payer looking at the screen. */
    val undoneByName: String? = null,
)

data class ReviewEditsState(
    val billTitle: String,
    val currency: String,
    val edits: List<ReviewEditUi>,
    /** The bill's total as it stands now, which already includes every live change below. */
    val currentTotalSubunits: Long,
) {
    val live: List<ReviewEditUi> get() = edits.filter { it.decision != PendingEditDecision.UNDONE }
    val undone: List<ReviewEditUi> get() = edits.filter { it.decision == PendingEditDecision.UNDONE }
}

/**
 * "N changes to the bill" — what web guests changed after the receipt was scanned, and the way to take
 * any of it back (WEB_CLAIM_SPEC.md §3.9.1).
 *
 * **This screen reports; it does not adjudicate.** Every change listed has already applied. The payer is
 * told because an item edit moves the bill total and therefore everyone's money, and joining a claim
 * (which moves two people's, with both at the table) is not announced at all. That asymmetry is
 * deliberate and documented in §2.7; do not harmonise them.
 *
 * **There is no "undo all".** Each change is somebody's money and gets its own decision. And there is no
 * rights hierarchy: anyone on the bill may undo, an undo is itself an attributed entry, and the log is
 * the tiebreak. DI-free.
 */
@Composable
fun BillReviewEditsScreen(
    state: ReviewEditsState,
    onBack: () -> Unit = {},
    onUndo: (editId: String) -> Unit = {},
    /** Set when an undo could not be saved. Shown rather than swallowed: the tap looked like it worked. */
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
                        headline(state.live.size),
                        color = c.ink,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.live.isEmpty()) "Nobody has changed anything."
                        else "People edited the bill after it was scanned. The bill already includes these.",
                        color = c.ink2,
                        fontSize = 13.5.sp,
                    )
                }
            }

            items(state.live, key = { it.id }) { edit ->
                EditCard(edit, state.currency, onUndo = { onUndo(edit.id) })
            }

            if (state.live.isNotEmpty()) {
                item { TotalNote(state) }
            }

            if (state.undone.isNotEmpty()) {
                item {
                    Text(
                        "Undone",
                        color = c.ink3,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                items(state.undone, key = { "d-${it.id}" }) { edit -> UndoneCard(edit) }
            }
        }
    }
}

private fun headline(liveCount: Int): String = when (liveCount) {
    0 -> "No changes to the bill"
    1 -> "1 change to the bill"
    else -> "$liveCount changes to the bill"
}

/** Who did what, in one line: "Purity added Mango sticky rice". The item name carries the blue. */
@Composable
private fun EditCard(edit: ReviewEditUi, currency: String, onUndo: () -> Unit) {
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
            UndoChip(onClick = onUndo)
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

/** Deliberately quiet. Undo is available, not recommended: the overwhelming majority of these edits are
 *  someone fixing a genuine OCR miss, and a loud control invites second-guessing every one of them. */
@Composable
private fun UndoChip(onClick: () -> Unit) {
    val c = EvenlyTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EvIcon(EvIcons.Undo, size = 15.dp, tint = c.ink2)
        Text("Undo", color = c.ink2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** What has been taken back, kept on screen so a mis-tap is visible rather than vanishing. Anyone on the
 *  bill can undo, so this is also how the payer sees that a guest undid somebody else's change. */
@Composable
private fun UndoneCard(edit: ReviewEditUi) {
    val c = EvenlyTheme.colors
    EvCard(fill = true, bordered = true, padded = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EvIcon(EvIcons.Undo, size = 16.dp, tint = c.ink3)
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "${edit.proposerName} ${verb(edit.kind)} ${edit.itemLabel}",
                    color = c.ink2,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (edit.undoneByName != null) "Undone by ${edit.undoneByName}. The bill is back to what it was."
                    else "Undone. The bill is back to what it was.",
                    color = c.ink3,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** The one place the arithmetic is spelled out, so nobody has to add up card deltas in their head.
 *
 *  Blue, not amber. The old amber note said the bill was holding its scanned amounts until the payer
 *  decided, which is now false; and there is nothing here to warn about, only a number to state. */
@Composable
private fun TotalNote(state: ReviewEditsState) {
    val c = EvenlyTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.blueTint)
            .border(1.dp, c.blueTint2, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EvIcon(EvIcons.Info, size = 16.dp, tint = c.blueText, modifier = Modifier.padding(top = 2.dp))
        Text(
            buildAnnotatedString {
                append("The bill is ")
                withStyle(SpanStyle(fontFamily = EvenlyTheme.monoFamily, fontWeight = FontWeight.Bold)) {
                    append(moneySubunits(state.currentTotalSubunits, state.currency))
                }
                append(" with all of these. Undo anything that looks wrong.")
            },
            color = c.ink2,
            fontSize = 12.5.sp,
        )
    }
}
