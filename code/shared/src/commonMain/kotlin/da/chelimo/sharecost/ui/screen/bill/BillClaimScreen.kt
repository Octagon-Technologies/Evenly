package da.chelimo.sharecost.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCheck
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScSheetScaffold
import da.chelimo.sharecost.ui.components.StatusBarScrim
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** A person the bill is for — the pool the "Share with…" picker draws from. */
data class ClaimParticipantUi(val userId: String, val name: String, val isMe: Boolean)

/** One line on the claim screen, from the current user's perspective. */
data class ClaimItemUi(
    val id: String,
    val label: String,
    val quantity: Int,
    val unitPriceSubunits: Long,
    val myQuantity: Int,
    val othersQuantity: Int,
    val otherNames: List<String>,
    val shareMemberIds: Set<String> = emptySet(),
    val shareMemberNames: List<String> = emptyList(),
) {
    val leftoverUnits: Int get() = (quantity - myQuantity - othersQuantity).coerceAtLeast(0)
}

data class ClaimBillState(
    val title: String,
    val currency: String,
    val yourTabSubunits: Long,
    val totalSubunits: Long,
    val claimedSubunits: Long,
    val items: List<ClaimItemUi>,
    val participants: List<ClaimParticipantUi> = emptyList(),
    val myUserId: String? = null,
    val imDone: Boolean = false,
    val livePeople: Int = 0,
) {
    /** A line needs someone when nothing (individual or shared) covers it. */
    val unclaimedCount: Int get() = items.count { it.myQuantity + it.othersQuantity == 0 && it.shareMemberIds.isEmpty() }
}

/**
 * The live "Split the bill" claim screen. Each person taps what they had — a checkbox for single dishes,
 * a 0-based counter for multi-unit dishes — and can tap "Share" to split a line with specific people
 * (the auto-union set). Their tab updates live. DI-free.
 */
@Composable
fun BillClaimScreen(
    state: ClaimBillState,
    onBack: () -> Unit = {},
    onSetClaim: (itemId: String, quantity: Int) -> Unit = { _, _ -> },
    onSetShareMember: (itemId: String, userId: String, inShare: Boolean) -> Unit = { _, _, _ -> },
    onEditBill: () -> Unit = {},
    onAskGroup: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var unclaimedOnly by remember { mutableStateOf(false) }
    var shareTargetId by remember { mutableStateOf<String?>(null) }
    val shown = if (unclaimedOnly) state.items.filter { it.myQuantity + it.othersQuantity == 0 && it.shareMemberIds.isEmpty() } else state.items
    val progress = if (state.totalSubunits > 0L) (state.claimedSubunits.toFloat() / state.totalSubunits).coerceIn(0f, 1f) else 0f

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.page)) {
            StatusBarScrim()
            ScTopBar(
                title = state.title,
                navIcon = { ScIconButton(ScIcons.Back, onBack) },
                actions = {
                    if (state.livePeople > 0) Text("${state.livePeople} here", color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                },
                showDivider = false,
            )

            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your tab", color = c.ink2, fontSize = 13.sp)
                Text(moneySubunits(state.yourTabSubunits, state.currency), color = c.blue, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
                Text("tax & tip included", color = c.ink3, fontSize = 12.sp)
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp)).background(c.surface)) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(c.blue))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.unclaimedCount == 0) "Everything's claimed" else "${state.unclaimedCount} ${if (state.unclaimedCount == 1) "dish" else "dishes"} still need someone",
                        color = c.ink2, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    if (state.unclaimedCount > 0) {
                        Text(
                            if (unclaimedOnly) "Show all" else "Show only these",
                            color = c.blue, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { unclaimedOnly = !unclaimedOnly }.padding(4.dp),
                        )
                    }
                }
            }

            Text("Tap what you had", color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(shown, key = { it.id }) { item ->
                    ClaimRow(item, state.currency, state.myUserId, onSetClaim, onShare = { shareTargetId = item.id })
                }
            }

            Column(Modifier.fillMaxWidth().topHairline(c.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("Tax & gratuity by share · tip split evenly", color = c.ink3, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScButton("Edit bill", onEditBill, variant = ButtonVariant.Secondary, leadingIcon = ScIcons.Edit, modifier = Modifier.weight(1f), small = true)
                    ScButton("Ask the group", onAskGroup, variant = ButtonVariant.Secondary, leadingIcon = ScIcons.Comment, modifier = Modifier.weight(1f), small = true)
                }
                ScButton(if (state.imDone) "Done ✓ — tap to reopen" else "I'm done", onDone, variant = ButtonVariant.Primary)
            }
        }

        val target = shareTargetId?.let { id -> state.items.firstOrNull { it.id == id } }
        if (target != null) {
            SharePickerSheet(
                item = target,
                participants = state.participants,
                currency = state.currency,
                onToggle = { userId, inShare -> onSetShareMember(target.id, userId, inShare) },
                onDismiss = { shareTargetId = null },
            )
        }
    }
}

@Composable
private fun ClaimRow(
    item: ClaimItemUi,
    currency: String,
    myUserId: String?,
    onSetClaim: (String, Int) -> Unit,
    onShare: () -> Unit,
) {
    val c = ShareCostTheme.colors
    val mine = item.myQuantity > 0 || (myUserId != null && myUserId in item.shareMemberIds)
    val shared = item.shareMemberIds.isNotEmpty()
    val orphan = item.myQuantity + item.othersQuantity == 0 && !shared
    val bg = when {
        mine -> c.blueTint
        orphan -> c.credit.copy(alpha = 0.12f)
        else -> c.page
    }
    val isCounter = item.quantity >= 2
    val sub = buildString {
        append(moneySubunits(item.unitPriceSubunits, currency))
        if (isCounter) append(" each")
        when {
            orphan -> append(" · no one yet")
            shared -> append(" · shared by ${item.shareMemberNames.size}")
            item.otherNames.isNotEmpty() -> append(" · ${item.otherNames.joinToString(", ")}")
        }
        if (isCounter && item.leftoverUnits > 0 && !shared) append(" · ${item.leftoverUnits} left")
    }

    Row(
        Modifier.fillMaxWidth().topHairline(c.border).background(bg).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.label, color = if (orphan) c.credit else c.ink, fontSize = 15.sp, fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal)
            Text(sub, color = if (orphan) c.credit else c.ink3, fontSize = 12.sp)
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onShare).padding(top = 3.dp, end = 6.dp, bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ScIcon(ScIcons.Users, size = 13.dp, tint = c.blue)
                Text(if (shared) "Sharing ›" else "Share with…", color = c.blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (isCounter) {
            Stepper(value = item.myQuantity, min = 0, onChange = { onSetClaim(item.id, it) })
        } else {
            ScCheck(checked = item.myQuantity > 0, onCheckedChange = { onSetClaim(item.id, if (it) 1 else 0) })
        }
    }
}

/** "Who split this?" — pick the people sharing a line; toggling writes the auto-union set live. */
@Composable
private fun SharePickerSheet(
    item: ClaimItemUi,
    participants: List<ClaimParticipantUi>,
    currency: String,
    onToggle: (userId: String, inShare: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = ShareCostTheme.colors
    val count = item.shareMemberIds.size
    val leftoverPool = item.leftoverUnits.toLong() * item.unitPriceSubunits
    val perHead = if (count > 0) leftoverPool / count else leftoverPool
    ScSheetScaffold(onDismiss = onDismiss, title = "Who split ${item.label}?") {
        if (count > 0) {
            Text(
                "${moneySubunits(if (item.leftoverUnits > 0) leftoverPool else item.unitPriceSubunits, currency)} ÷ $count = ${moneySubunits(perHead, currency)} each",
                color = c.ink2, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), textAlign = TextAlign.Center,
            )
        }
        participants.forEach { p ->
            val inShare = p.userId in item.shareMemberIds
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onToggle(p.userId, !inShare) }
                    .background(if (inShare) c.blueTint else c.page).padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (p.isMe) "You" else p.name, color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                ScCheck(checked = inShare, onCheckedChange = { onToggle(p.userId, it) })
            }
            Box(Modifier.height(6.dp))
        }
        Box(Modifier.height(4.dp))
        ScButton("Done", onDismiss, variant = ButtonVariant.Primary)
    }
}
