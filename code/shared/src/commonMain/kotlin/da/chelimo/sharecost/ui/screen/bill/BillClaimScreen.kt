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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCheck
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.StatusBarScrim
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.theme.ShareCostTheme

/** One line on the claim screen, from the current user's perspective. */
data class ClaimItemUi(
    val id: String,
    val label: String,
    val quantity: Int,
    val unitPriceSubunits: Long,
    val myQuantity: Int,
    val othersQuantity: Int,
    val otherNames: List<String>,
)

data class ClaimBillState(
    val title: String,
    val currency: String,
    val yourTabSubunits: Long,
    val totalSubunits: Long,
    val claimedSubunits: Long,
    val items: List<ClaimItemUi>,
    val livePeople: Int = 0,
) {
    val unclaimedCount: Int get() = items.count { it.myQuantity + it.othersQuantity == 0 }
}

/**
 * The live "Split the bill" claim screen. Each person taps what they had; their tab updates at the top.
 * Single-unit dishes use a checkbox; multi-unit dishes use a counter that starts at 0, so the question
 * is always "how many did you have," never the ambiguous "am I in or out." DI-free.
 */
@Composable
fun BillClaimScreen(
    state: ClaimBillState,
    onBack: () -> Unit = {},
    onSetClaim: (itemId: String, quantity: Int) -> Unit = { _, _ -> },
    onEditBill: () -> Unit = {},
    onAskGroup: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var unclaimedOnly by remember { mutableStateOf(false) }
    val shown = if (unclaimedOnly) state.items.filter { it.myQuantity + it.othersQuantity == 0 } else state.items
    val progress = if (state.totalSubunits > 0L) (state.claimedSubunits.toFloat() / state.totalSubunits).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxSize().background(c.page)) {
        StatusBarScrim()
        ScTopBar(
            title = state.title,
            navIcon = { ScIconButton(ScIcons.Back, onBack) },
            actions = {
                if (state.livePeople > 0) {
                    Text("${state.livePeople} here", color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                }
            },
            showDivider = false,
        )

        // Your tab
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your tab", color = c.ink2, fontSize = 13.sp)
            Text(moneySubunits(state.yourTabSubunits, state.currency), color = c.blue, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
            Text("tax & tip included", color = c.ink3, fontSize = 12.sp)
        }

        // Progress
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
            items(shown, key = { it.id }) { item -> ClaimRow(item, state.currency, onSetClaim) }
        }

        // Bottom action bar
        Column(Modifier.fillMaxWidth().topHairline(c.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("Tax & gratuity by share · tip split evenly", color = c.ink3, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ScButton("Edit bill", onEditBill, variant = ButtonVariant.Secondary, leadingIcon = ScIcons.Edit, modifier = Modifier.weight(1f), small = true)
                ScButton("Ask the group", onAskGroup, variant = ButtonVariant.Secondary, leadingIcon = ScIcons.Comment, modifier = Modifier.weight(1f), small = true)
            }
            ScButton(if (state.unclaimedCount == 0) "Finish" else "Finish anyway", onFinish, variant = ButtonVariant.Primary)
        }
    }
}

@Composable
private fun ClaimRow(item: ClaimItemUi, currency: String, onSetClaim: (String, Int) -> Unit) {
    val c = ShareCostTheme.colors
    val mine = item.myQuantity > 0
    val orphan = item.myQuantity + item.othersQuantity == 0
    val left = item.quantity - item.myQuantity - item.othersQuantity
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
            item.otherNames.isNotEmpty() -> append(" · ${item.otherNames.joinToString(", ")}")
        }
        if (isCounter && left > 0) append(" · $left left")
    }

    val rowMod = if (!isCounter) {
        Modifier.clickable { onSetClaim(item.id, if (mine) 0 else 1) }
    } else Modifier

    Row(
        Modifier.fillMaxWidth().topHairline(c.border).background(bg).then(rowMod).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.label, color = if (orphan) c.credit else c.ink, fontSize = 15.sp, fontWeight = if (mine) FontWeight.SemiBold else FontWeight.Normal)
            Text(sub, color = if (orphan) c.credit else c.ink3, fontSize = 12.sp)
        }
        if (isCounter) {
            Stepper(value = item.myQuantity, min = 0, onChange = { onSetClaim(item.id, it) })
        } else {
            ScCheck(checked = mine, onCheckedChange = { onSetClaim(item.id, if (it) 1 else 0) })
        }
    }
}
