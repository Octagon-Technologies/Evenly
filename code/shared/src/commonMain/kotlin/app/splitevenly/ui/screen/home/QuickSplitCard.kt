package app.splitevenly.ui.screen.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.balance.Debt
import app.splitevenly.domain.expense.ExpenseWithShares
import app.splitevenly.domain.expense.SPLIT_MODE_ITEMIZED
import app.splitevenly.domain.group.Group
import app.splitevenly.platform.isIOS
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvContextMenu
import app.splitevenly.ui.components.EvMenuAction
import app.splitevenly.ui.components.EvSwipeActions
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.money
import app.splitevenly.ui.screen.expense.DELETE_QUICK_SPLIT_BODY
import app.splitevenly.ui.screen.expense.DeleteBillSheet
import app.splitevenly.ui.theme.EvenlyTheme
import kotlin.math.abs

/** How long a fully settled quick split stays on Home before it moves to History. */
internal const val QUICK_SPLIT_HISTORY_DELAY_MS: Long = 24L * 60 * 60 * 1000

/**
 * The quick-split half of a Home card. [expenseId] is null while the bill has not been saved yet (the
 * names step finished, the editor did not), and tapping the card resumes the editor.
 */
data class QuickCardUi(
    val expenseId: String?,
    val people: List<String>,
    val paid: Int,
    val owing: Int,
    val allSettled: Boolean,
    val settledAt: Long?,
    val itemized: Boolean = false,
) {
    fun inHistory(now: Long): Boolean = allSettled && settledAt != null && now - settledAt >= QUICK_SPLIT_HISTORY_DELAY_MS
}

/**
 * A quick split's Home card. "Paid" counts the people other than the payer whose share is non-zero and
 * fully paid; someone who has not claimed anything yet on an itemized bill is not paid. "All settled"
 * also needs the whole bill handed out, or an itemized bill nobody has claimed would read as settled.
 */
internal fun quickSplitCard(
    group: Group,
    viewer: UserId,
    memberNames: List<String?>,
    debts: List<Debt>,
    bill: ExpenseWithShares?,
    lastPaymentAt: Long?,
): GroupCardUi {
    val netSubunits =
        debts.sumOf { d ->
            when (viewer) {
                d.creditorUserId -> d.amountSubunits
                d.debtorUserId -> -d.amountSubunits
                else -> 0L
            }
        }
    val payer = bill?.expense?.payerUserId
    val others = bill?.shares.orEmpty().filter { it.userId != payer }
    val owing = (memberNames.size - 1).coerceAtLeast(others.size)
    val paid = others.count { it.owedSubunits > 0 && it.remainingSubunits == 0L }
    val handedOut = bill != null && bill.shares.sumOf { it.owedSubunits } >= bill.expense.amountSubunits
    val allSettled = handedOut && others.all { it.remainingSubunits == 0L }
    val quick =
        QuickCardUi(
            expenseId = bill?.expense?.id?.value,
            people = memberNames.map { it?.takeIf(String::isNotBlank) ?: "?" },
            paid = paid,
            owing = owing,
            allSettled = allSettled,
            settledAt = if (allSettled) lastPaymentAt ?: bill?.expense?.createdAt else null,
            itemized = bill?.expense?.splitMode == SPLIT_MODE_ITEMIZED,
        )
    return GroupCardUi(
        id = group.id.value,
        emoji = "🧾",
        name = bill?.expense?.title?.takeIf(String::isNotBlank) ?: group.name,
        members = memberNames.size.coerceAtLeast(1),
        status =
            when {
                bill == null -> GroupBalanceStatus.Empty
                allSettled -> GroupBalanceStatus.Settled
                netSubunits > 0L -> GroupBalanceStatus.Owed
                netSubunits < 0L -> GroupBalanceStatus.Owe
                else -> GroupBalanceStatus.Settled
            },
        amount = if (netSubunits == 0L) null else abs(netSubunits) / 100.0,
        last =
            when {
                bill == null -> "Not finished yet"
                allSettled -> "All settled"
                owing == 0 -> "Just you so far"
                else -> "$paid of $owing paid"
            },
        quick = quick,
    )
}

/**
 * A quick split's Home card with its long-press menu. Delete removes the whole one-time split (its
 * hidden group), since deleting only the bill would leave an empty "Not finished yet" card behind.
 * Edit is offered only once the bill has been saved; before that, Open already resumes the editor.
 * Archive and Delete are also on a left swipe ([homeSwipeActions]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickSplitCardWithMenu(
    g: GroupCardUi,
    onOpen: () -> Unit,
    onEdit: (expenseId: String, itemized: Boolean) -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit = {},
    swipeOpen: Boolean = false,
    onSwipeOpenChange: (Boolean) -> Unit = {},
) {
    val c = EvenlyTheme.colors
    val quick = g.quick ?: return
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val highlight = menuOpen && !isIOS()
    Box {
        EvSwipeActions(
            open = swipeOpen,
            onOpenChange = onSwipeOpenChange,
            actions = homeSwipeActions(onArchive = onArchive, onDelete = { confirmDelete = true }),
        ) {
            EvCard(
                modifier = if (highlight) Modifier.border(1.5.dp, c.blueText, RoundedCornerShape(16.dp)) else Modifier,
                fill = true,
                bordered = true,
            ) {
                QuickSplitRow(g, onClick = onOpen, onLongClick = { menuOpen = true })
            }
        }
        EvContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            actions =
                buildList {
                    add(EvMenuAction("Open", EvIcons.ChevR, onClick = onOpen))
                    quick.expenseId?.let { id ->
                        add(EvMenuAction(if (quick.itemized) "Edit bill" else "Edit expense", EvIcons.Edit) { onEdit(id, quick.itemized) })
                    }
                    add(EvMenuAction("Archive", EvIcons.Archive, onClick = onArchive))
                    add(EvMenuAction("Delete", EvIcons.Trash, destructive = true) { confirmDelete = true })
                },
        )
    }
    if (confirmDelete) {
        DeleteBillSheet(
            body = DELETE_QUICK_SPLIT_BODY,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A quick split on Home: the receipt tile, the bill's title, who's in it, and how many have paid. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickSplitRow(
    g: GroupCardUi,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    val quick = g.quick ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
            EvIcon(EvIcons.Receipt, size = 22.dp, tint = c.blueText)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(g.name, style = t.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InitialsStack(quick.people)
                Text(g.last, style = t.description, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when (g.status) {
            GroupBalanceStatus.Empty -> {
                EvIcon(EvIcons.ChevR, size = 18.dp, tint = c.ink3)
            }

            GroupBalanceStatus.Settled -> {
                Text("Settled", style = t.chip.copy(color = c.settled))
            }

            else -> {
                val owed = g.status == GroupBalanceStatus.Owed
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (owed) "you're owed" else "you owe", style = t.chip.copy(color = if (owed) c.settled else c.owe))
                    EvChip(money(g.amount ?: 0.0), variant = if (owed) ChipVariant.Owed else ChipVariant.Owe, mono = true)
                }
            }
        }
    }
}

/** Up to four overlapping initials, so the card says "these people" without a member list. */
@Composable
private fun InitialsStack(names: List<String>) {
    val c = EvenlyTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        names.take(4).forEach { name ->
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(c.blueTint2)
                    .border(1.5.dp, c.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), color = c.blueText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
