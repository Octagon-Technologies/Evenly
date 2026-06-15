package da.chelimo.sharecost.ui.screen.settle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScCheck
import da.chelimo.sharecost.ui.components.ScDivider
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScParticipantChip
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

data class SettleShareUi(val title: String, val date: String, val amountSubunits: Long)

/** 15 · Settle a person — 2 steps (design/src/screens-settle.jsx). Wired via SettlePersonRoute. */
@Composable
fun SettlePersonScreen(
    peerName: String = "Andrew",
    currencyCode: String = "USD",
    shares: List<SettleShareUi> = DemoSettleShares,
    onBack: () -> Unit = {},
    onOpenApp: (Long) -> Unit = {},
    onMarkPaid: (Long) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var step by remember { mutableStateOf(1) }
    var checked by remember { mutableStateOf(shares.indices.toSet()) }
    val total = shares.filterIndexed { i, _ -> i in checked }.sumOf { it.amountSubunits }

    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        if (step == 1) {
            ScTopBar("Settle with $peerName", subtitle = "in $currencyCode", navIcon = { ScIconButton(ScIcons.Back, onBack) })
            Column(Modifier.weight(1f).background(c.surface).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Pick which of your shares to pay back. You can settle one, some, or all.", color = c.ink2, fontSize = 12.sp)
                if (shares.isEmpty()) {
                    Text("Nothing to settle with $peerName.", color = c.ink2, fontSize = 14.sp)
                } else {
                    ScCard {
                        shares.forEachIndexed { i, s ->
                            Row(
                                Modifier.fillMaxWidth().then(if (i > 0) Modifier.topHairline(c.border) else Modifier).clickable { checked = if (i in checked) checked - i else checked + i }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ScCheck(i in checked)
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, color = c.ink, style = MaterialTheme.typography.titleSmall)
                                    if (s.date.isNotBlank()) Text(s.date, color = c.ink2, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(moneySubunits(s.amountSubunits, currencyCode), color = if (i in checked) c.ink else c.ink3, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(c.page)) {
                ScDivider()
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Total to settle", color = c.ink2, fontWeight = FontWeight.SemiBold)
                        Text(moneySubunits(total, currencyCode), color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = ShareCostTheme.monoFamily)
                    }
                    ScButton("Continue", { step = 2 }, leadingIcon = ScIcons.ChevR, enabled = total > 0)
                }
            }
        } else {
            ScTopBar("Pay $peerName", subtitle = "Step 2 of 2", navIcon = { ScIconButton(ScIcons.Back, { step = 1 }) })
            Column(Modifier.weight(1f).background(c.surface).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ScCard(padded = true) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScAvatar(peerName, size = AvatarSize.Lg)
                        Text(moneySubunits(total, currencyCode), color = c.ink, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily, letterSpacing = (-1).sp)
                        Text("to $peerName", color = c.ink2, fontSize = 12.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pay with", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettleHandles.forEach { h ->
                            ScParticipantChip(h.app, selected = h.app == "Venmo", leading = { ScIcon(ScIcons.Wallet, size = 15.dp) })
                        }
                    }
                }
                ScButton("Open in Venmo", { onOpenApp(total) }, leadingIcon = ScIcons.Link)
                ScButton("Mark paid manually", { onMarkPaid(total) }, variant = ButtonVariant.Text, leadingIcon = ScIcons.Check)
            }
        }
    }
}

internal val DemoSettleShares = listOf(
    SettleShareUi("Dinner at La Negra", "May 23", 2400),
    SettleShareUi("Cenote day trip", "May 22", 1800),
    SettleShareUi("Airport taxi", "May 21", 1450),
)

@Preview
@Composable
private fun SettlePersonPreview() {
    ShareCostTheme { SettlePersonScreen() }
}
