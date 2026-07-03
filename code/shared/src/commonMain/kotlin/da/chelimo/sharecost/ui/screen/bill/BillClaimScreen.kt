package da.chelimo.sharecost.ui.screen.bill

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCheck
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScSheetScaffold
import da.chelimo.sharecost.ui.components.ScToggle
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
    // Your tab, broken out so the header can explain it (Food + Tax + Tip = tab). Tax folds gratuity and
    // nets any discount, so the three always sum to [yourTabSubunits].
    val myFoodSubunits: Long = 0L,
    val myTaxSubunits: Long = 0L,
    val myTipSubunits: Long = 0L,
    val totalSubunits: Long,
    val claimedSubunits: Long,
    val items: List<ClaimItemUi>,
    val participants: List<ClaimParticipantUi> = emptyList(),
    val myUserId: String? = null,
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
    val filterActive = unclaimedOnly && state.unclaimedCount > 0
    // The list order is snapshotted ONCE when you open the bill — your picks grouped at the top, dishes
    // already settled by others at the bottom — then held stable while you claim. Reordering a card the
    // instant it's tapped is disorienting: it jumps out from under your finger and you lose your place in a
    // run of items you were working through. Deferring the regroup to the next open gives the "my stuff up
    // top" benefit without the mid-selection reflow.
    val order = remember {
        fun rank(it: ClaimItemUi) = when {
            it.claimedByMe(state.myUserId) -> 0
            it.fullyClaimedByOthers(state.myUserId) -> 2
            else -> 1
        }
        state.items.withIndex()
            .sortedWith(compareBy({ rank(it.value) }, { it.index }))
            .map { it.value.id }
            .withIndex().associate { (r, id) -> id to r }
    }
    val shown = (if (filterActive) state.items.filter { it.myQuantity + it.othersQuantity == 0 && it.shareMemberIds.isEmpty() } else state.items)
        .sortedBy { order[it.id] ?: Int.MAX_VALUE }
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
                // Break the tab into Food + Tax + Tip so the number is explainable — a $5 juice becoming
                // $6.94 shouldn't read as a bug. The three cells always sum to the tab. Before you've
                // claimed anything, prompt instead of showing an empty strip.
                if (state.yourTabSubunits > 0L || state.myFoodSubunits > 0L) {
                    TabBreakdownStrip(state.myFoodSubunits, state.myTaxSubunits, state.myTipSubunits, state.currency)
                } else {
                    Text("Tap what you had — tax & tip are added automatically", color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp)).background(c.surface)) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(99.dp)).background(c.blue))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            state.unclaimedCount == 0 -> "Everything's claimed"
                            unclaimedOnly -> "Showing only the ${state.unclaimedCount} that need someone"
                            else -> "${state.unclaimedCount} ${if (state.unclaimedCount == 1) "dish" else "dishes"} still need someone"
                        },
                        color = c.ink2, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    // The switch label spells out what flipping it ON does — narrow the list to just the
                    // unclaimed dishes — and goes blue while active, so its state *and* effect are obvious
                    // (the vague "Only these" gave no clue what "these" referred to).
                    if (state.unclaimedCount > 0) {
                        Text(
                            "Only unclaimed",
                            color = if (unclaimedOnly) c.blue else c.ink2,
                            fontSize = 13.sp,
                            fontWeight = if (unclaimedOnly) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        ScToggle(checked = unclaimedOnly, onCheckedChange = { unclaimedOnly = it })
                    }
                }
            }

            Text("Tap what you had", color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                items(shown, key = { it.id }) { item ->
                    ClaimCard(
                        item, state.currency, state.myUserId, onSetClaim,
                        onShare = {
                            // Initiating a split means you had the item and want to share it — so add
                            // yourself automatically (you can remove yourself in the sheet). Only when the
                            // share is still empty; joining an existing split stays an explicit "Add".
                            val me = state.myUserId
                            if (me != null && item.shareMemberIds.isEmpty()) onSetShareMember(item.id, me, true)
                            shareTargetId = item.id
                        },
                    )
                }
            }

            Column(Modifier.fillMaxWidth().topHairline(c.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScButton("Edit bill", onEditBill, variant = ButtonVariant.Secondary, leadingIcon = ScIcons.Edit, modifier = Modifier.weight(1f), small = true)
                    ScButton("Ask the group", onAskGroup, variant = ButtonVariant.Secondary, leadingIcon = ScIcons.Comment, modifier = Modifier.weight(1f), small = true)
                }
                ScButton("Done", onDone, variant = ButtonVariant.Primary)
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

/** I've claimed this line — a unit is mine, or I'm in its shared set. */
private fun ClaimItemUi.claimedByMe(myUserId: String?): Boolean =
    myQuantity > 0 || (myUserId != null && myUserId in shareMemberIds)

/** Fully covered by other people (nothing left for me) — these sink to the bottom and read as inactive. */
private fun ClaimItemUi.fullyClaimedByOthers(myUserId: String?): Boolean =
    !claimedByMe(myUserId) && (shareMemberIds.isNotEmpty() || leftoverUnits == 0)

/**
 * One dish as a card. Three states: **white** = active/available, **blue outline + a gentle lift** =
 * you've picked it, **grey** = fully claimed by others (inactive). No shouty fills.
 */
@Composable
private fun ClaimCard(
    item: ClaimItemUi,
    currency: String,
    myUserId: String?,
    onSetClaim: (String, Int) -> Unit,
    onShare: () -> Unit,
) {
    val c = ShareCostTheme.colors
    val mine = item.claimedByMe(myUserId)
    val claimed = item.fullyClaimedByOthers(myUserId)
    val shared = item.shareMemberIds.isNotEmpty()
    val iShare = myUserId != null && myUserId in item.shareMemberIds
    val orphan = item.myQuantity + item.othersQuantity == 0 && !shared
    val isCounter = item.quantity >= 2
    val sub = buildString {
        append(moneySubunits(item.unitPriceSubunits, currency))
        if (isCounter) append(" each")
        when {
            orphan -> append(" · no one yet")
            shared -> append(" · split evenly") // who's in it is spelled out on the share pill below
            item.otherNames.isNotEmpty() -> append(" · ${item.otherNames.joinToString(", ")}")
        }
        if (isCounter && item.leftoverUnits > 0 && !shared) append(" · ${item.leftoverUnits} left")
    }

    val shape = RoundedCornerShape(14.dp)
    val bg = if (claimed) c.surface else c.page
    val borderColor = if (mine) c.blue else c.border
    val borderWidth = if (mine) 1.5.dp else 1.dp
    Row(
        Modifier.fillMaxWidth()
            // Only the picked card lifts, and barely — a quiet cue, not a callout.
            .then(if (mine) Modifier.shadow(2.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .background(bg)
            .border(borderWidth, borderColor, shape)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.label, color = if (claimed) c.ink2 else c.ink, fontSize = 15.sp, fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal)
            Text(sub, color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 1.dp))
            Box(Modifier.padding(top = 8.dp)) {
                ShareButton(shared = shared, iShare = iShare, count = item.shareMemberIds.size, onClick = onShare)
            }
        }
        // Multi-unit → stepper (individual units, coexists with a leftover share). Single unit → a checkbox,
        // but ONLY while it isn't shared: once it's split, participation is the share pill and a separate
        // checkbox would just be the "what does the box mean now?" confusion the owner flagged.
        if (isCounter) {
            Box(Modifier.padding(start = 10.dp)) {
                Stepper(value = item.myQuantity, min = 0, onChange = { onSetClaim(item.id, it) })
            }
        } else if (!shared) {
            Box(Modifier.padding(start = 10.dp)) {
                ScCheck(checked = item.myQuantity > 0, onCheckedChange = { onSetClaim(item.id, if (it) 1 else 0) })
            }
        }
    }
}

/**
 * The share affordance, styled as a pill button and — crucially — stating your *role* plainly: an
 * invite when nobody shares it, "You + N sharing" when you're in the split, or "Split by N" when others
 * share it but you don't. Tapping always opens the picker.
 */
@Composable
private fun ShareButton(shared: Boolean, iShare: Boolean, count: Int, onClick: () -> Unit) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(999.dp)
    val bg: Color
    val borderColor: Color?
    val fg: Color
    val label: String
    when {
        iShare -> {
            val others = count - 1
            bg = c.blue; borderColor = null; fg = c.onAccent
            label = if (others > 0) "You + $others sharing ›" else "You're sharing ›"
        }
        shared -> { bg = c.surface; borderColor = c.borderStrong; fg = c.ink2; label = "Split by $count ›" }
        else -> { bg = c.blueTint; borderColor = c.blueTint2; fg = c.blue; label = "Share with…" }
    }
    Row(
        Modifier.clip(shape).background(bg)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ScIcon(ScIcons.Users, size = 13.dp, tint = fg)
        Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The Food · Tax · Tip breakdown under the tab. Cells always sum to the tab (tax folds gratuity/discount). */
@Composable
private fun TabBreakdownStrip(food: Long, tax: Long, tip: Long, currency: String) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.padding(top = 8.dp).clip(shape).border(1.dp, c.border, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BreakdownCell("Food", food, currency)
        BreakdownDivider()
        BreakdownCell("Tax", tax, currency)
        BreakdownDivider()
        BreakdownCell("Tip", tip, currency)
    }
}

@Composable
private fun BreakdownCell(label: String, amount: Long, currency: String) {
    val c = ShareCostTheme.colors
    Column(
        Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(label, color = c.ink3, fontSize = 11.sp)
        Text(moneySubunits(amount, currency), color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
    }
}

@Composable
private fun BreakdownDivider() {
    Box(Modifier.width(1.dp).height(30.dp).background(ShareCostTheme.colors.border))
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
    // A single unit is split whole (individual claims on it are ignored once shared); a multi-unit line
    // shares only its leftover units. Either way the pool is what the share set divides.
    val poolSubunits = if (item.quantity == 1) item.unitPriceSubunits else item.leftoverUnits.toLong() * item.unitPriceSubunits
    val perHead = if (count > 0) poolSubunits / count else poolSubunits
    val mathLine = if (count > 0)
        "${moneySubunits(poolSubunits, currency)} ÷ $count = ${moneySubunits(perHead, currency)} each"
    else null
    ScSheetScaffold(onDismiss = onDismiss, title = "Who's splitting ${item.label}?", sub = mathLine) {
        Text(
            "Tap a person to split it with them. Tap again to remove.",
            color = c.ink3, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), textAlign = TextAlign.Center,
        )
        participants.forEach { p ->
            val inShare = p.userId in item.shareMemberIds
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onToggle(p.userId, !inShare) }
                    .background(if (inShare) c.blueTint else c.page).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScAvatar(name = if (p.isMe) "You" else p.name, me = p.isMe, size = AvatarSize.Sm)
                Text(
                    if (p.isMe) "You" else p.name, color = c.ink, fontSize = 15.sp,
                    fontWeight = if (inShare) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f),
                )
                SharePersonToggle(inShare)
            }
            Box(Modifier.height(6.dp))
        }
        Box(Modifier.height(4.dp))
        ScButton("Done", onDismiss, variant = ButtonVariant.Primary)
    }
}

/** The per-person in/out control — an explicit "Add" / "Sharing" pill, not an ambiguous checkbox. */
@Composable
private fun SharePersonToggle(inShare: Boolean) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(999.dp)
    if (inShare) {
        Row(
            Modifier.clip(shape).background(c.blue).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ScIcon(ScIcons.Check, size = 13.dp, tint = c.onAccent)
            Text("Sharing", color = c.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Row(
            Modifier.clip(shape).border(1.dp, c.blue, shape).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ScIcon(ScIcons.Plus, size = 13.dp, tint = c.blue)
            Text("Add", color = c.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
