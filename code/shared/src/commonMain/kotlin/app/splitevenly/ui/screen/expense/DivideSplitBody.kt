package app.splitevenly.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.splitevenly.ui.components.AvatarSize
import app.splitevenly.ui.components.ChipVariant
import app.splitevenly.ui.components.EvAvatar
import app.splitevenly.ui.components.EvButton
import app.splitevenly.ui.components.EvCard
import app.splitevenly.ui.components.EvChip
import app.splitevenly.ui.components.EvSegmented
import app.splitevenly.ui.components.clickableClearingFocus
import app.splitevenly.ui.components.icon.EvIcon
import app.splitevenly.ui.components.icon.EvIcons
import app.splitevenly.ui.components.moneySubunits
import app.splitevenly.ui.components.topHairline
import app.splitevenly.ui.theme.EvenlyTheme

/**
 * Editor state for the "Split one amount" body: the total to divide plus the raw per-participant split
 * inputs. An absent share count means 1×; %/Exact are held as text so the user can clear and retype
 * (parsed live), and are seeded by [seed] on first entry to that mode.
 *
 * Everything derived from these ([owed], [isValid], [shares]) takes the selected `ids` as an argument
 * rather than holding them, so the participant selection stays owned by the screen.
 */
@Stable
internal class DivideSplitState(
    prefill: AddExpensePrefill?,
) {
    var amountText by mutableStateOf(prefill?.let { format2dp(it.amountSubunits / 100.0) } ?: "")
    var splitLabel by mutableStateOf((prefill?.mode ?: SplitMode.Even).label)
    var shareUnits by mutableStateOf(prefill?.shareUnits ?: emptyMap<String, Int>())
    var percentText by mutableStateOf(prefill?.percentText ?: emptyMap<String, String>())
    var exactText by mutableStateOf(prefill?.exactText ?: emptyMap<String, String>())

    val mode: SplitMode get() = SplitMode.fromLabel(splitLabel)
    val amountSubunits: Long get() = parseAmountSubunits(amountText)

    private fun units(ids: List<String>) = ids.associateWith { shareUnits[it] ?: 1 }

    private fun percents(ids: List<String>) = ids.associateWith { parsePercent(percentText[it]) }

    private fun exact(ids: List<String>) = ids.associateWith { parseAmountSubunits(exactText[it].orEmpty()) }

    /** The per-participant owed map — the live preview and exactly what [shares] emits on Save (D-28). */
    fun owed(ids: List<String>): Map<String, Long> = splitOwed(mode, amountSubunits, ids, units(ids), percents(ids), exact(ids))

    /** Sum of the typed percentages in hundredths of a percent (an exact 100% reads as `10_000`). */
    fun percentScaled(ids: List<String>): Long = percentTotalScaled(ids, percents(ids))

    /** Sum of the typed exact amounts (subunits). */
    fun exactTotal(ids: List<String>): Long = exactTotalSubunits(ids, exact(ids))

    /** Save is gated on this: % must sum to 100.00 and Exact to the total (AC-INV-001). */
    fun splitValid(ids: List<String>): Boolean =
        when (mode) {
            SplitMode.Even -> true
            SplitMode.Share -> units(ids).values.sum() > 0
            SplitMode.Percent -> percentScaled(ids) == 10_000L
            SplitMode.Exact -> exactTotal(ids) == amountSubunits
        }

    /**
     * Seeds the editable %/Exact fields when first switching into that mode (or when the participant set
     * changes, which would otherwise leave the totals stale). Even/Share need no seed.
     */
    fun seed(
        ids: List<String>,
        selected: Set<String>,
    ) {
        if (mode == SplitMode.Percent && percentText.keys != selected) {
            percentText = distributeRemainder(ids, emptyMap()).mapValues { format2dp(it.value) }
        }
        if (mode == SplitMode.Exact && exactText.keys != selected) {
            val even = splitOwed(SplitMode.Even, amountSubunits, ids)
            exactText = ids.associateWith { format2dp((even[it] ?: 0L) / 100.0) }
        }
    }

    /** The computed owed amounts plus the raw inputs that produced them, for the emitted submit. */
    fun shares(ids: List<String>): List<SplitShareInput> {
        val owed = owed(ids)
        return ids.map { id ->
            SplitShareInput(
                userId = id,
                owedSubunits = owed[id] ?: 0L,
                units = if (mode == SplitMode.Share) (shareUnits[id] ?: 1) else null,
                percent = if (mode == SplitMode.Percent) parsePercent(percentText[id]) else null,
                exactSubunits = if (mode == SplitMode.Exact) parseAmountSubunits(exactText[id].orEmpty()) else null,
            )
        }
    }
}

/**
 * "Divide the total": the amount to split, the method (Even/Share/%/Exact), the per-person preview, and
 * the live math footer for the variable modes. What's shown here is exactly what Save emits.
 */
@Composable
internal fun DivideSplitBody(
    state: DivideSplitState,
    selectedList: List<AddParticipantUi>,
    currency: String,
    symbol: String,
    showErrors: Boolean,
    saving: Boolean,
    onCurrencyClick: () -> Unit,
    onSave: () -> Unit,
) {
    val c = EvenlyTheme.colors
    val focusManager = LocalFocusManager.current
    val ids = selectedList.map { it.userId }
    val mode = state.mode
    val amountSubunits = state.amountSubunits
    val owed = state.owed(ids)

    EvCard(padded = true) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(symbol, color = c.ink3, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = EvenlyTheme.monoFamily)
                BasicTextField(
                    value = state.amountText,
                    onValueChange = { state.amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    textStyle = EvenlyTheme.amounts.input.copy(color = c.ink),
                    singleLine = true,
                    cursorBrush = SolidColor(c.blue),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    decorationBox = { inner ->
                        Box {
                            if (state.amountText.isEmpty()) Text("0.00", style = EvenlyTheme.amounts.input, color = c.ink3)
                            inner()
                        }
                    },
                )
            }
            EvChip(
                currency,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickableClearingFocus(onClick = onCurrencyClick),
                variant = ChipVariant.Ghost,
                leadingIcon = EvIcons.Globe,
            )
        }
    }
    if (showErrors && amountSubunits <= 0) {
        Text("Enter an amount", color = c.danger, fontSize = 12.sp)
    }
    Text("How to split", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    EvSegmented(options = SplitMode.labels, selected = state.splitLabel, onSelect = { state.splitLabel = it })
    EvCard(modifier = Modifier.padding(top = 4.dp)) {
        selectedList.forEachIndexed { i, p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (i >
                            0
                        ) {
                            Modifier.topHairline(c.border)
                        } else {
                            Modifier
                        },
                    ).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EvAvatar(p.name, me = p.isMe, size = AvatarSize.Sm)
                Text(p.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                when (mode) {
                    SplitMode.Even -> {
                        Text(
                            moneySubunits(owed[p.userId] ?: 0L, currency),
                            color = c.ink,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = EvenlyTheme.monoFamily,
                        )
                    }

                    SplitMode.Share -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            UnitStepper(units = state.shareUnits[p.userId] ?: 1, onChange = {
                                state.shareUnits =
                                    state.shareUnits + (p.userId to it)
                            })
                            Text(
                                moneySubunits(owed[p.userId] ?: 0L, currency),
                                color = c.ink3,
                                fontFamily = EvenlyTheme.monoFamily,
                                fontSize = 14.sp,
                            )
                        }
                    }

                    SplitMode.Percent -> {
                        InlineNumberField(
                            value = state.percentText[p.userId].orEmpty(),
                            onValueChange = { state.percentText = state.percentText + (p.userId to sanitizeDecimal(it)) },
                            width = 88.dp,
                            suffix = "%",
                        )
                    }

                    SplitMode.Exact -> {
                        InlineNumberField(
                            value = state.exactText[p.userId].orEmpty(),
                            onValueChange = { state.exactText = state.exactText + (p.userId to sanitizeDecimal(it)) },
                            width = 104.dp,
                            prefix = symbol,
                        )
                    }
                }
            }
        }
        if (selectedList.isEmpty()) {
            Text("Add at least one participant", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
    }
    // live math footer for the variable modes
    if (mode == SplitMode.Percent && selectedList.isNotEmpty()) {
        val percentScaled = state.percentScaled(ids)
        val ok = percentScaled == 10_000L
        // What's still unallocated for the % to add up to 100 (negative = over-assigned).
        val remainingScaled = 10_000L - percentScaled
        val over = remainingScaled < 0
        val statusTint =
            if (ok) {
                c.blue
            } else if (over) {
                c.danger
            } else {
                c.warning
            }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EvIcon(
                    if (ok) {
                        EvIcons.CheckCircle
                    } else if (over) {
                        EvIcons.Alert
                    } else {
                        EvIcons.Info
                    },
                    size = 15.dp,
                    tint = statusTint,
                )
                Text(
                    when {
                        ok -> "All assigned"
                        over -> "${format2dp(-remainingScaled / 100.0)}% over"
                        else -> "${format2dp(remainingScaled / 100.0)}% left to assign"
                    },
                    color = statusTint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!ok) {
                Text(
                    "Distribute remainder",
                    color = c.blueText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickableClearingFocus {
                                state.percentText =
                                    distributeRemainder(ids, ids.associateWith { parsePercent(state.percentText[it]) })
                                        .mapValues { format2dp(it.value) }
                            }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
    if (mode == SplitMode.Exact && selectedList.isNotEmpty()) {
        // diff > 0 → still to hand out; diff < 0 → over the total (a share must come down).
        val exactTotal = state.exactTotal(ids)
        val diff = amountSubunits - exactTotal
        val ok = diff == 0L
        val over = diff < 0
        val statusTint =
            if (ok) {
                c.blue
            } else if (over) {
                c.danger
            } else {
                c.warning
            }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EvIcon(
                    if (ok) {
                        EvIcons.CheckCircle
                    } else if (over) {
                        EvIcons.Alert
                    } else {
                        EvIcons.Info
                    },
                    size = 15.dp,
                    tint = statusTint,
                )
                Text(
                    when {
                        ok -> "All assigned"
                        over -> "${moneySubunits(-diff, currency)} over"
                        else -> "${moneySubunits(diff, currency)} left to assign"
                    },
                    color = statusTint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!ok) {
                Text(
                    "${moneySubunits(exactTotal, currency)} of ${moneySubunits(amountSubunits, currency)}",
                    color = c.ink3,
                    fontSize = 12.sp,
                    fontFamily = EvenlyTheme.monoFamily,
                )
            }
        }
    }
    // Save right under the per-person split — where you look when you're done (A4).
    EvButton(if (saving) "Saving…" else "Save expense", onSave, enabled = !saving)
}

/** A compact `[-] n× [+]` integer stepper for SHARE weights; clamps at a minimum of one share. */
@Composable
private fun UnitStepper(
    units: Int,
    onChange: (Int) -> Unit,
) {
    val c = EvenlyTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.height(34.dp).clip(shape).border(1.dp, c.borderStrong, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(EvIcons.Minus, enabled = units > 1) { onChange((units - 1).coerceAtLeast(1)) }
        Text(
            "$units×",
            color = c.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = EvenlyTheme.monoFamily,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(30.dp),
        )
        StepButton(EvIcons.Plus, enabled = true) { onChange(units + 1) }
    }
}

@Composable
private fun StepButton(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = EvenlyTheme.colors
    Box(
        Modifier.size(32.dp).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { EvIcon(icon, size = 16.dp, tint = if (enabled) c.ink2 else c.ink3) }
}

/** A fixed-width, right-aligned mono `.sc-input` for inline %/Exact entry. Blue ring on focus. */
@Composable
private fun InlineNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    width: Dp,
    prefix: String? = null,
    suffix: String? = null,
) {
    val c = EvenlyTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = c.ink, fontSize = 15.sp, fontFamily = EvenlyTheme.monoFamily, textAlign = TextAlign.End),
        cursorBrush = SolidColor(c.blue),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        decorationBox = { inner ->
            Row(
                Modifier
                    .width(width)
                    .height(38.dp)
                    .clip(shape)
                    .background(c.page)
                    .border(if (focused) 2.dp else 1.dp, if (focused) c.blue else c.borderStrong, shape)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                prefix?.let { Text(it, color = c.ink3, fontSize = 15.sp, fontFamily = EvenlyTheme.monoFamily) }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            "0",
                            color = c.ink3,
                            fontSize = 15.sp,
                            fontFamily = EvenlyTheme.monoFamily,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    inner()
                }
                suffix?.let { Text(it, color = c.ink3, fontSize = 15.sp, fontFamily = EvenlyTheme.monoFamily) }
            }
        },
    )
}
