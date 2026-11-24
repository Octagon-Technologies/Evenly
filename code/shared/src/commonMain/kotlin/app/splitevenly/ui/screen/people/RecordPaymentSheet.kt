package app.splitevenly.ui.screen.people

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.splitevenly.domain.people.Person
import app.splitevenly.domain.people.SplitTarget
import app.splitevenly.domain.people.checkSplit
import app.splitevenly.domain.people.oldestFirst
import app.splitevenly.ui.components.ButtonVariant
import app.splitevenly.ui.components.EvAmountInput
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvSheetScaffold
import app.splitevenly.ui.components.amountTextToSubunits
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.theme.EvenlyTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** "40.00" for an editable field; no symbol, no thousands separator. */
private fun plain(subunits: Long): String = "${subunits / 100}.${(subunits % 100).toString().padStart(2, '0')}"

/**
 * "Purity paid you": one amount spread over the groups they owe you in. Oldest first by default.
 * "Change" makes every group editable, and editing one never moves the others. Save stays live; when the
 * split doesn't add up it shakes the bar and outlines any group set above its balance, and writes
 * nothing.
 */
@Composable
fun RecordPaymentSheet(
    person: Person,
    onDismiss: () -> Unit,
    onSave: (currency: String, split: Map<String, Long>) -> Unit,
) {
    val c = EvenlyTheme.colors
    val t = EvenlyTheme.text
    val currencies = remember(person) { person.totals.entries.sortedByDescending { it.value }.map { it.key } }
    var currency by remember { mutableStateOf(currencies.firstOrNull() ?: "USD") }
    val targets =
        remember(person, currency) {
            person.groups.filter { it.currency == currency }.map { SplitTarget(it.groupId, it.amountSubunits, it.oldestDate) }
        }
    val owed = targets.sumOf { it.balanceSubunits }
    var amountText by remember(currency) { mutableStateOf(plain(owed)) }
    val paid = amountTextToSubunits(amountText)
    var editing by remember(currency) { mutableStateOf(false) }
    val edits = remember(currency) { mutableStateMapOf<String, String>() }
    val split: Map<String, Long> =
        if (editing) targets.associate { it.groupId to amountTextToSubunits(edits[it.groupId] ?: "") } else oldestFirst(paid, targets)
    val check = checkSplit(paid, split, targets)
    var showProblems by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    EvSheetScaffold(onDismiss = onDismiss, title = "${person.name} paid you", dimBackground = true) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (currencies.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    currencies.forEach { code ->
                        val on = code == currency
                        Text(
                            code,
                            style = t.chip.copy(color = if (on) c.onAccent else c.ink2),
                            modifier =
                                Modifier
                                    .background(if (on) c.blue else c.surface, RoundedCornerShape(99.dp))
                                    .clickable { currency = code }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            EvAmountInput(
                text = amountText,
                onTextChange = { amountText = it },
                currency = currency,
                helper = "They owe you ${moneySubunits(owed, currency)} in all.",
            )
            // One group has nothing to split: the amount is the payment. The box only earns its place at two.
            if (targets.size > 1) Column(
                Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row {
                    Text(if (editing) "Your split" else "Oldest first", style = t.caption.copy(color = c.ink3), modifier = Modifier.weight(1f))
                    Text(
                        if (editing) "Oldest first" else "Change",
                        style = t.caption.copy(color = c.blueText, fontWeight = FontWeight.SemiBold),
                        modifier =
                            Modifier.clickable {
                                if (!editing) {
                                    split.forEach { (g, v) -> edits[g] = plain(v) }
                                } else {
                                    edits.clear()
                                }
                                editing = !editing
                                showProblems = false
                            },
                    )
                }
                targets.sortedBy { it.oldestDate }.forEach { target ->
                    val group = person.groups.first { it.groupId == target.groupId && it.currency == currency }
                    val value = split[target.groupId] ?: 0L
                    val bad = showProblems && target.groupId in check.overBalance
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${group.groupEmoji} ${group.groupName}", style = t.body)
                            val left = target.balanceSubunits - value
                            Text(
                                if (left <= 0) "Clears it" else "${moneySubunits(left, currency)} left of ${moneySubunits(target.balanceSubunits, currency)}",
                                style = t.caption.copy(color = c.ink3),
                            )
                        }
                        val shape = RoundedCornerShape(8.dp)
                        val boxMod =
                            Modifier
                                .widthIn(min = 84.dp)
                                .background(c.page, shape)
                                .border(1.5.dp, if (bad) c.danger else c.border, shape)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        if (editing) {
                            BasicTextField(
                                value = edits[target.groupId] ?: "",
                                onValueChange = { edits[target.groupId] = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                textStyle = t.rowValue.copy(color = if (bad) c.danger else c.ink, textAlign = TextAlign.End),
                                cursorBrush = SolidColor(c.blue),
                                modifier = boxMod.width(84.dp),
                            )
                        } else {
                            Text(moneySubunits(value, currency), style = t.rowValue.copy(textAlign = TextAlign.End), modifier = boxMod)
                        }
                    }
                }
            }
            val left = check.leftSubunits
            val ok = check.addsUp
            Row(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(shake.value.roundToInt(), 0) }
                    .background(if (ok) c.settledTint else c.creditTint, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                val tint = if (ok) c.settled else c.credit
                Text(
                    "${moneySubunits(check.assignedSubunits, currency)} of ${moneySubunits(paid, currency)}",
                    style = t.caption.copy(color = tint, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        paid <= 0 -> "Enter an amount"
                        check.overBalance.isNotEmpty() && showProblems -> "A group is over"
                        left > 0 -> "${moneySubunits(left, currency)} left"
                        left < 0 -> "${moneySubunits(-left, currency)} over"
                        else -> "Adds up"
                    },
                    style = t.caption.copy(color = tint, fontWeight = FontWeight.SemiBold),
                )
            }
            EvButton(
                variant = ButtonVariant.PrimarySolid,
                onClick = {
                    if (check.addsUp) {
                        onSave(currency, split.filterValues { it > 0 })
                    } else {
                        showProblems = true
                        scope.launch {
                            for (x in listOf(-10f, 10f, -6f, 6f, 0f)) shake.animateTo(x, tween(55))
                        }
                    }
                },
            ) { Text("Save payment") }
            Spacer(Modifier.width(0.dp))
        }
    }
}
