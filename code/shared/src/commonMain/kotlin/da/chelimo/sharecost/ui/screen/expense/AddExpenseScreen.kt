package da.chelimo.sharecost.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScChip
import da.chelimo.sharecost.ui.components.ScField
import da.chelimo.sharecost.ui.components.ScIconButton
import da.chelimo.sharecost.ui.components.ScModalScaffold
import da.chelimo.sharecost.ui.components.ScParticipantChip
import da.chelimo.sharecost.ui.components.ScSegmented
import da.chelimo.sharecost.ui.components.ScSelectField
import da.chelimo.sharecost.ui.components.ScTextField
import da.chelimo.sharecost.ui.components.ScTopBar
import da.chelimo.sharecost.ui.components.currencySymbol
import da.chelimo.sharecost.ui.components.moneySubunits
import da.chelimo.sharecost.ui.components.topHairline
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import kotlin.math.roundToLong

/** A participant the expense can be split between (real members are passed by the route). */
data class AddParticipantUi(val userId: String, val name: String, val isMe: Boolean)

/**
 * 13 · Add / edit expense (design/src/screens-addexpense.jsx) — wired for the EVEN split (the most
 * common). Amount/title/participants are live; Save emits the parsed amount + selected ids so the
 * route runs the allocator and persists. The Share/%/Exact modes are shown but persist as even for
 * now (a follow-up slice runs the full split editor over the existing allocator).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddExpenseScreen(
    editing: Boolean = false,
    participants: List<AddParticipantUi> = DemoParticipants,
    currencyCode: String = "USD",
    saving: Boolean = false,
    onBack: () -> Unit = {},
    onSave: (amountSubunits: Long, title: String, payerUserId: String, selectedUserIds: List<String>) -> Unit = { _, _, _, _ -> },
    onAddPlaceholder: (String) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    val symbol = currencySymbol(currencyCode)
    var amountText by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var split by remember { mutableStateOf("Even") }
    var selected by remember { mutableStateOf(participants.map { it.userId }.toSet()) }
    var known by remember { mutableStateOf(participants.map { it.userId }.toSet()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var payerId by remember { mutableStateOf("") }
    var showPayerDialog by remember { mutableStateOf(false) }
    val effectivePayerId = participants.firstOrNull { it.userId == payerId }?.userId
        ?: participants.firstOrNull { it.isMe }?.userId
        ?: participants.firstOrNull()?.userId
        ?: ""
    val payer = participants.firstOrNull { it.userId == effectivePayerId }
    // Auto-select members that appear after a placeholder is added, without re-selecting ones the user deselected.
    LaunchedEffect(participants) {
        val fresh = participants.map { it.userId }.toSet() - known
        if (fresh.isNotEmpty()) { selected = selected + fresh; known = known + fresh }
    }

    val amountSubunits = parseAmountSubunits(amountText)
    val selectedList = participants.filter { it.userId in selected }
    val even = evenSplit(amountSubunits, selectedList.map { it.userId })
    val canSave = amountSubunits > 0 && title.isNotBlank() && selected.isNotEmpty() && !saving

    Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        ScTopBar(
            title = if (editing) "Edit expense" else "New expense",
            navIcon = { ScIconButton(ScIcons.Close, onBack) },
            actions = {
                val bg = if (canSave) c.blue else c.blueTint2
                val fg = if (canSave) c.onAccent else c.disabledInk
                Box(
                    Modifier.clip(RoundedCornerShape(11.dp)).background(bg)
                        .then(if (canSave) Modifier.clickable { onSave(amountSubunits, title.trim(), effectivePayerId, selected.toList()) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text("Save", color = fg, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // amount (editable, calculator-style)
            ScCard(padded = true) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(symbol, color = c.ink3, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                        BasicTextField(
                            value = amountText,
                            onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            textStyle = ShareCostTheme.amounts.input.copy(color = c.ink),
                            singleLine = true,
                            cursorBrush = SolidColor(c.blue),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            decorationBox = { inner ->
                                Box {
                                    if (amountText.isEmpty()) Text("0.00", style = ShareCostTheme.amounts.input, color = c.ink3)
                                    inner()
                                }
                            },
                        )
                    }
                    ScChip(currencyCode, variant = ChipVariant.Ghost, leadingIcon = ScIcons.Globe)
                }
            }

            ScField("Title") { ScTextField(title, { title = it }, placeholder = "What was it for?") }

            ScField("Paid by") {
                ScSelectField(payer?.name ?: "You", { showPayerDialog = true }, leading = { ScAvatar(payer?.name ?: "You", me = payer?.isMe == true, size = AvatarSize.Sm) })
            }

            // participants
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Participants", color = c.ink2, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    participants.forEach { p ->
                        val on = p.userId in selected
                        ScParticipantChip(
                            p.name, selected = on,
                            leading = { ScAvatar(p.name, me = p.isMe, size = AvatarSize.Xs) },
                            trailing = if (on) ({ ScIcon(ScIcons.Check, size = 14.dp) }) else null,
                            onClick = { selected = if (on) selected - p.userId else selected + p.userId },
                        )
                    }
                    ScParticipantChip("Add", selected = false, leading = { ScIcon(ScIcons.Plus, size = 15.dp, tint = c.blue) }, onClick = { showAddDialog = true })
                }
            }

            // split
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Split", color = c.ink2, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                ScSegmented(options = listOf("Even", "Share", "%", "Exact"), selected = split, onSelect = { split = it })
                ScCard(modifier = Modifier.padding(top = 4.dp)) {
                    selectedList.forEachIndexed { i, p ->
                        Row(
                            Modifier.fillMaxWidth().then(if (i > 0) Modifier.topHairline(c.border) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ScAvatar(p.name, me = p.isMe, size = AvatarSize.Sm)
                            Text(p.name, color = c.ink, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(moneySubunits(even[p.userId] ?: 0L, currencyCode), color = c.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
                        }
                    }
                    if (selectedList.isEmpty()) {
                        Text("Add at least one participant", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                    }
                }
                if (split == "Share" || split == "%" || split == "Exact") {
                    Text("Share / % / Exact arrive next — this saves as an even split for now.", color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }

    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        ScModalScaffold(onDismiss = { showAddDialog = false }) {
            Text("Add a participant", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            ScField("Name") { ScTextField(newName, { newName = it }, placeholder = "e.g. Bob") }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                ScButton("Add", { if (newName.isNotBlank()) { onAddPlaceholder(newName.trim()); showAddDialog = false } }, enabled = newName.isNotBlank())
            }
        }
    }

    if (showPayerDialog) {
        ScModalScaffold(onDismiss = { showPayerDialog = false }) {
            Text("Who paid?", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            participants.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { payerId = p.userId; showPayerDialog = false }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScAvatar(p.name, me = p.isMe, size = AvatarSize.Sm)
                    Text(p.name, color = c.ink, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (p.userId == effectivePayerId) ScIcon(ScIcons.Check, size = 18.dp, tint = c.blue)
                }
            }
        }
    }
}

private fun parseAmountSubunits(text: String): Long {
    val cleaned = text.replace(",", "").trim()
    if (cleaned.isEmpty()) return 0
    val value = cleaned.toDoubleOrNull() ?: return 0
    return (value * 100.0).roundToLong()
}

/** Even split with largest-remainder distribution (first `rem` ids get +1 subunit). Sums to total. */
internal fun evenSplit(totalSubunits: Long, ids: List<String>): Map<String, Long> {
    if (ids.isEmpty() || totalSubunits <= 0) return emptyMap()
    val base = totalSubunits / ids.size
    val rem = (totalSubunits % ids.size).toInt()
    return ids.mapIndexed { i, id -> id to (base + if (i < rem) 1L else 0L) }.toMap()
}

private val DemoParticipants = listOf(
    AddParticipantUi("u1", "You", true),
    AddParticipantUi("u2", "Andrew", false),
    AddParticipantUi("u3", "Bob", false),
    AddParticipantUi("u4", "Maya", false),
)

@Preview
@Composable
private fun AddExpensePreview() {
    ShareCostTheme { AddExpenseScreen() }
}
