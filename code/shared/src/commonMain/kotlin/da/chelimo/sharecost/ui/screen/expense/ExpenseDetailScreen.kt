package da.chelimo.sharecost.ui.screen.expense

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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ButtonVariant
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScDivider
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScModalScaffold
import da.chelimo.sharecost.ui.components.ScProgress
import da.chelimo.sharecost.ui.components.ScSectionLabel
import da.chelimo.sharecost.ui.components.ScSkeleton
import da.chelimo.sharecost.ui.components.ScSkeletonRow
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme

enum class ExpenseDetailState { Loading, Content, Error }

data class DetailShareUi(
    val name: String,
    val owedSubunits: Long,
    val paidSubunits: Long,
    val remainingSubunits: Long,
    val me: Boolean = false,
    val payer: Boolean = false,
)

/** 12 · Expense detail (design/src/screens-expense.jsx). */
@Composable
fun ExpenseDetailScreen(
    state: ExpenseDetailState = ExpenseDetailState.Content,
    title: String = "Dinner at La Negra",
    category: String = "Food & Drink",
    payerName: String = "Andrew",
    dateLabel: String = "May 23 · 8:40 PM",
    amountSubunits: Long = 9600,
    remainingSubunits: Long = 4800,
    currencyCode: String = "USD",
    splitLabel: String = "Split between 4 · even",
    splitRows: List<DetailShareUi> = DemoSplit,
    onBack: () -> Unit = {},
    onSettleThis: () -> Unit = {},
    onReload: () -> Unit = {},
) {
    val c = ShareCostTheme.colors
    var overflow by remember { mutableStateOf(false) }
    val split = splitRows

    Column(Modifier.fillMaxSize().background(if (state == ExpenseDetailState.Content) c.surface else c.page).systemBarsPadding()) {
        ScTopBarDetail(
            title = if (state == ExpenseDetailState.Error) "" else title,
            sub = if (state == ExpenseDetailState.Content) category.ifBlank { null } else null,
            onBack = onBack,
            onMore = if (state == ExpenseDetailState.Content) ({ overflow = true }) else null,
        )
        when (state) {
            ExpenseDetailState.Loading -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ScSkeleton(height = 132.dp, radius = 16.dp)
                ScSkeleton(width = 140.dp, height = 14.dp)
                ScSkeletonRow(); ScSkeletonRow(); ScSkeletonRow()
            }
            ExpenseDetailState.Error -> ErrorContent(onReload = onReload)
            ExpenseDetailState.Content -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // header card
                ScCard(padded = true) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            if (remainingSubunits != amountSubunits) {
                                Text(moneySubunits(amountSubunits, currencyCode), style = ShareCostTheme.amounts.original.copy(fontSize = 14.sp), color = c.ink3)
                            }
                            Text(moneySubunits(remainingSubunits, currencyCode), style = ShareCostTheme.amounts.hero, color = c.ink)
                            Text("remaining of original", color = c.ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (category.isNotBlank()) {
                            ScChip(category.substringBefore(" "), variant = ChipVariant.Blue, leadingIcon = ScIcons.Food, large = true)
                        }
                    }
                    ScDivider(Modifier.padding(vertical = 14.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScAvatar(payerName, me = payerName == "You", size = AvatarSize.Sm)
                            Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(payerName) }; append(" paid") }, color = c.ink, fontSize = 14.sp)
                        }
                        Text(dateLabel, color = c.ink2, fontSize = 12.sp)
                    }
                }

                // receipts
                Column {
                    ScSectionLabel("Receipts")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(2) {
                            Box(Modifier.size(width = 84.dp, height = 108.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(12.dp)), contentAlignment = Alignment.BottomCenter) {
                                Text("receipt", color = c.ink3, fontSize = 10.sp, fontFamily = ShareCostTheme.monoFamily, modifier = Modifier.padding(bottom = 8.dp))
                            }
                        }
                        Column(Modifier.size(width = 84.dp, height = 108.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).clickable { }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            ScIcon(ScIcons.Camera, size = 22.dp, tint = c.ink2)
                            Text("Add", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // split breakdown
                Column {
                    ScSectionLabel(splitLabel)
                    ScCard {
                        split.forEachIndexed { i, s ->
                            Row(
                                Modifier.fillMaxWidth().then(if (i > 0) Modifier.topHairline(c.border) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ScAvatar(s.name, me = s.me, size = AvatarSize.Sm)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.name) }
                                            if (s.payer) withStyle(SpanStyle(color = c.ink2, fontWeight = FontWeight.Medium)) { append(" · paid") }
                                            if (s.me) withStyle(SpanStyle(color = c.ink2, fontWeight = FontWeight.Medium)) { append(" · you") }
                                        },
                                        color = c.ink, fontSize = 15.sp,
                                    )
                                    Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.width(100.dp)) { ScProgress(if (s.owedSubunits > 0) s.paidSubunits.toFloat() / s.owedSubunits else 0f) }
                                        Text("Paid ${moneySubunits(s.paidSubunits, currencyCode)} of ${moneySubunits(s.owedSubunits, currencyCode)}", color = c.ink3, fontSize = 12.sp, fontFamily = ShareCostTheme.monoFamily)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(moneySubunits(s.remainingSubunits, currencyCode), color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                                    if (s.me && s.remainingSubunits > 0) ScButton("Settle this", onSettleThis, small = true)
                                }
                            }
                        }
                    }
                }

                // comments
                Column {
                    ScSectionLabel("Comments")
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CommentBubble("Maya", "I already sent Andrew \$16 in cash 🙌", "2h", me = false)
                        CommentBubble("You", "Nice — I'll settle my half tonight.", "1h", me = true)
                    }
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(c.page).border(1.dp, c.borderStrong, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text("Add a comment…", color = c.ink3, fontSize = 15.sp)
                        }
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.blue).clickable { }, contentAlignment = Alignment.Center) {
                            ScIcon(ScIcons.Send, size = 18.dp, tint = c.onAccent)
                        }
                    }
                }

                Collapsible("History", ScIcons.History, "3 events")
                Collapsible("Refunds", ScIcons.Refund, "None yet")
            }
        }
    }

    if (overflow) {
        ScModalScaffold(onDismiss = { overflow = false }) {
            listOf(ScIcons.Edit to "Edit", ScIcons.Refund to "Issue refund", ScIcons.Camera to "Add receipt", ScIcons.Share to "Share").forEach { (ic, label) ->
                OverflowRow(ic, label, c.ink2, c.ink) { overflow = false }
            }
            OverflowRow(ScIcons.Trash, "Delete", c.danger, c.danger) { overflow = false }
        }
    }
}

@Composable
private fun ScTopBarDetail(title: String, sub: String?, onBack: () -> Unit, onMore: (() -> Unit)?) {
    val c = ShareCostTheme.colors
    Column(Modifier.fillMaxWidth().background(c.page)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScIconButton(ScIcons.Back, onBack)
            Column(Modifier.weight(1f)) {
                if (title.isNotEmpty()) Text(title, color = c.ink, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (sub != null) Text(sub, color = c.ink2, fontSize = 12.sp)
            }
            if (onMore != null) ScIconButton(ScIcons.More, onMore)
        }
        ScDivider()
    }
}

@Composable
private fun CommentBubble(name: String, text: String, time: String, me: Boolean) {
    val c = ShareCostTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
        if (!me) ScAvatar(name, size = AvatarSize.Sm)
        Column(Modifier.padding(horizontal = 8.dp).widthIn(max = 250.dp), horizontalAlignment = if (me) Alignment.End else Alignment.Start) {
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (me) c.blue else c.surface).padding(horizontal = 13.dp, vertical = 9.dp)) {
                Text(text, color = if (me) c.onAccent else c.ink, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Text("$name · $time", color = c.ink2, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        if (me) ScAvatar(name, me = true, size = AvatarSize.Sm)
    }
}

@Composable
private fun Collapsible(title: String, icon: ImageVector, sub: String) {
    val c = ShareCostTheme.colors
    var open by remember { mutableStateOf(false) }
    ScCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScIcon(icon, size = 20.dp, tint = c.ink2)
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(sub, color = c.ink2, fontSize = 12.sp)
            ScIcon(if (open) ScIcons.ChevU else ScIcons.ChevD, size = 16.dp, tint = c.ink3)
        }
        if (open) {
            Box(Modifier.topHairline(c.border).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
                Text("Andrew added this expense · May 23, 8:40 PM", color = c.ink2, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun OverflowRow(icon: ImageVector, label: String, iconTint: androidx.compose.ui.graphics.Color, textColor: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ScIcon(icon, size = 20.dp, tint = iconTint)
        Text(label, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ErrorContent(onReload: () -> Unit) {
    val c = ShareCostTheme.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(c.dangerTint), contentAlignment = Alignment.Center) {
            ScIcon(ScIcons.Alert, size = 30.dp, tint = c.danger)
        }
        Text("Couldn't load this expense", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text("Something went wrong on our side. Check your connection and try again.", color = c.ink2, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 240.dp))
        Column(Modifier.widthIn(max = 240.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ScButton("Reload", onReload, leadingIcon = ScIcons.Reload)
            ScButton("Send feedback", {}, variant = ButtonVariant.Text, leadingIcon = ScIcons.Mail)
        }
    }
}

private val DemoSplit = listOf(
    DetailShareUi("You", owedSubunits = 2400, paidSubunits = 0, remainingSubunits = 2400, me = true),
    DetailShareUi("Andrew", owedSubunits = 2400, paidSubunits = 2400, remainingSubunits = 0, payer = true),
    DetailShareUi("Bob", owedSubunits = 2400, paidSubunits = 0, remainingSubunits = 2400),
    DetailShareUi("Maya", owedSubunits = 2400, paidSubunits = 1600, remainingSubunits = 800),
)

@Preview
@Composable
private fun ExpenseDetailPreview() {
    ShareCostTheme { ExpenseDetailScreen() }
}
