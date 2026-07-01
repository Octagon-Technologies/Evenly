package da.chelimo.sharecost.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
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
import da.chelimo.sharecost.domain.expense.CategoryDefaults
import da.chelimo.sharecost.domain.expense.GroupCategory
import da.chelimo.sharecost.platform.PickSource
import da.chelimo.sharecost.ui.screen.group.CategoryCatalog
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** A participant the expense can be split between (real members are passed by the route). */
data class AddParticipantUi(val userId: String, val name: String, val isMe: Boolean)

/** A locally-picked receipt held on the editor until the expense exists; uploaded in the background on save. */
data class PickedReceiptUi(val isPdf: Boolean)

/**
 * 13 · Add / edit expense (design/src/screens-addexpense.jsx) — the full split editor. Amount, title,
 * participants and the four split modes are all live: [splitOwed] (D-28) drives the per-participant
 * preview and is exactly what Save emits, so what's shown is what's persisted. Save is gated until the
 * split is well-formed (% sums to 100.00, Exact sums to the total — AC-INV-001). The route maps the
 * emitted [AddExpenseSubmit] straight onto `NewExpense`, preserving the raw share inputs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddExpenseScreen(
    editing: Boolean = false,
    participants: List<AddParticipantUi> = DemoParticipants,
    categories: List<GroupCategory> = CategoryDefaults.all,
    currencyCode: String = "USD",
    saving: Boolean = false,
    prefill: AddExpensePrefill? = null,
    receipts: List<PickedReceiptUi> = emptyList(),
    receiptsEnabled: Boolean = false,
    onBack: () -> Unit = {},
    onSave: (AddExpenseSubmit) -> Unit = {},
    onAddPlaceholder: (String) -> Unit = {},
    onPickReceipt: (PickSource) -> Unit = {},
    onRemoveReceipt: (Int) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    // Flips true the first time Save is tapped while incomplete — the gaps then turn red.
    var showErrors by remember { mutableStateOf(false) }
    var showReceiptSource by remember { mutableStateOf(false) }
    // Currency is editable (F2): seed from the group base, let the user pick a foreign currency.
    var currency by remember { mutableStateOf(currencyCode) }
    val symbol = currencySymbol(currency)
    var showCurrencyDialog by remember { mutableStateOf(false) }
    var categoryId by remember { mutableStateOf(prefill?.categoryId) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    // On edit, seed every field from the saved expense so the editor re-renders the exact split.
    var amountText by remember { mutableStateOf(prefill?.let { format2dp(it.amountSubunits / 100.0) } ?: "") }
    var title by remember { mutableStateOf(prefill?.title ?: "") }
    var split by remember { mutableStateOf((prefill?.mode ?: SplitMode.Even).label) }
    var selected by remember { mutableStateOf(prefill?.selectedUserIds ?: participants.map { it.userId }.toSet()) }
    var known by remember { mutableStateOf(participants.map { it.userId }.toSet()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var payerId by remember { mutableStateOf(prefill?.payerUserId ?: "") }
    // Non-blank when an outside party paid (no group member). Such a payer is not part of the split.
    var outsidePayerName by remember { mutableStateOf(prefill?.payerOutsideName) }
    var showPayerDialog by remember { mutableStateOf(false) }
    // Raw per-participant split inputs. An absent share count means 1×; %/Exact fields are text so the
    // user can clear and retype (parsed live). %/Exact are seeded on first entry to that mode below.
    var shareUnits by remember { mutableStateOf(prefill?.shareUnits ?: emptyMap()) }
    var percentText by remember { mutableStateOf(prefill?.percentText ?: emptyMap()) }
    var exactText by remember { mutableStateOf(prefill?.exactText ?: emptyMap()) }

    val effectivePayerId = participants.firstOrNull { it.userId == payerId }?.userId
        ?: participants.firstOrNull { it.isMe }?.userId
        ?: participants.firstOrNull()?.userId
        ?: ""
    val payer = participants.firstOrNull { it.userId == effectivePayerId }
    val isOutsidePayer = !outsidePayerName.isNullOrBlank()
    val payerDisplayName = if (isOutsidePayer) outsidePayerName!! else (payer?.name ?: "You")
    // Auto-select members that appear after a placeholder is added, without re-selecting ones the user deselected.
    LaunchedEffect(participants) {
        val fresh = participants.map { it.userId }.toSet() - known
        if (fresh.isNotEmpty()) { selected = selected + fresh; known = known + fresh }
    }

    val mode = SplitMode.fromLabel(split)
    val amountSubunits = parseAmountSubunits(amountText)
    val selectedList = participants.filter { it.userId in selected }
    val ids = selectedList.map { it.userId }

    // Seed the editable %/Exact fields when first switching into that mode (or when the participant set
    // changes, which would otherwise leave the totals stale). Even/Share need no seed.
    LaunchedEffect(split, selected) {
        if (mode == SplitMode.Percent && percentText.keys != selected) {
            percentText = distributeRemainder(ids, emptyMap()).mapValues { format2dp(it.value) }
        }
        if (mode == SplitMode.Exact && exactText.keys != selected) {
            val even = splitOwed(SplitMode.Even, amountSubunits, ids)
            exactText = ids.associateWith { format2dp((even[it] ?: 0L) / 100.0) }
        }
    }

    val units = ids.associateWith { shareUnits[it] ?: 1 }
    val percents = ids.associateWith { parsePercent(percentText[it]) }
    val exact = ids.associateWith { parseAmountSubunits(exactText[it].orEmpty()) }
    val owed = splitOwed(mode, amountSubunits, ids, units, percents, exact)

    val percentScaled = percentTotalScaled(ids, percents)
    val exactTotal = exactTotalSubunits(ids, exact)
    val splitValid = when (mode) {
        SplitMode.Even -> true
        SplitMode.Share -> units.values.sum() > 0
        SplitMode.Percent -> percentScaled == 10_000L
        SplitMode.Exact -> exactTotal == amountSubunits
    }
    val isValid = amountSubunits > 0 && title.isNotBlank() && selected.isNotEmpty() && splitValid
    // What's still missing, phrased for the hint that shows above the form until the expense can be saved.
    val missing = buildList {
        if (amountSubunits <= 0) add("an amount")
        if (title.isBlank()) add("a title")
        if (selected.isEmpty()) add("a participant")
        if (amountSubunits > 0 && selected.isNotEmpty() && !splitValid) add("a valid split")
    }

    Column(Modifier.fillMaxSize().background(c.surface).systemBarsPadding()) {
        ScTopBar(
            title = if (editing) "Edit expense" else "New expense",
            navIcon = { ScIconButton(ScIcons.Close, onBack) },
            actions = {
                // Button stays live; validate on tap and reveal the gaps rather than sitting dead + greyed.
                val active = !saving
                val bg = if (active) c.blue else c.blueTint2
                val fg = if (active) c.onAccent else c.disabledInk
                Box(
                    Modifier.clip(RoundedCornerShape(11.dp)).background(bg)
                        .then(if (active) Modifier.clickable {
                            if (!isValid) {
                                showErrors = true
                                scope.launch { scrollState.animateScrollTo(0) }
                                return@clickable
                            }
                            onSave(
                                AddExpenseSubmit(
                                    amountSubunits = amountSubunits,
                                    title = title.trim(),
                                    payerUserId = if (isOutsidePayer) "" else effectivePayerId,
                                    payerOutsideName = if (isOutsidePayer) outsidePayerName else null,
                                    mode = mode,
                                    currency = currency,
                                    categoryId = categoryId,
                                    shares = ids.map { id ->
                                        SplitShareInput(
                                            userId = id,
                                            owedSubunits = owed[id] ?: 0L,
                                            units = if (mode == SplitMode.Share) (shareUnits[id] ?: 1) else null,
                                            percent = if (mode == SplitMode.Percent) parsePercent(percentText[id]) else null,
                                            exactSubunits = if (mode == SplitMode.Exact) parseAmountSubunits(exactText[id].orEmpty()) else null,
                                        )
                                    },
                                ),
                            )
                        } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(if (saving) "Saving…" else "Save", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // A quiet, always-present hint of what Save still needs — so the button is never a silent dead end.
            if (!isValid && !saving) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (showErrors) c.danger.copy(alpha = 0.10f) else c.blueTint)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScIcon(ScIcons.Info, size = 15.dp, tint = if (showErrors) c.danger else c.blue)
                    Text(
                        "Add ${missing.joinToString(" and ")} to save",
                        color = if (showErrors) c.danger else c.blue, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
            // amount (editable, calculator-style)
            ScCard(padded = true) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(symbol, color = c.ink3, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily)
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
                    ScChip(
                        currency,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showCurrencyDialog = true },
                        variant = ChipVariant.Ghost,
                        leadingIcon = ScIcons.Globe,
                    )
                }
            }

            if (showErrors && amountSubunits <= 0) {
                Text("Enter an amount", color = c.danger, fontSize = 12.sp)
            }

            ScField("Title") {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ScTextField(title, { title = it }, placeholder = "What was it for?", isError = showErrors && title.isBlank())
                    if (showErrors && title.isBlank()) {
                        Text("Give it a title", color = c.danger, fontSize = 12.sp)
                    }
                }
            }

            // category (F2) — optional; drives the Balances "Spending by category" card. Collapsed to a
            // single tappable picker row (like "Paid by") so the editor stays compact and scannable.
            val selectedCategory = categories.firstOrNull { it.key == categoryId }
            ScField("Category") {
                ScSelectField(
                    selectedCategory?.label ?: "Add category",
                    { showCategoryDialog = true },
                    valueColor = if (selectedCategory != null) c.ink else c.ink3,
                    leading = {
                        if (selectedCategory != null) {
                            val catColor = Color(selectedCategory.colorHex)
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(catColor.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                                ScIcon(CategoryCatalog.icon(selectedCategory.iconToken), size = 15.dp, tint = catColor)
                            }
                        } else {
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                                ScIcon(ScIcons.Tag, size = 15.dp, tint = c.blue)
                            }
                        }
                    },
                )
            }

            ScField("Paid by") {
                ScSelectField(
                    payerDisplayName,
                    { showPayerDialog = true },
                    leading = {
                        if (isOutsidePayer) {
                            Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                                ScIcon(ScIcons.User, size = 15.dp, tint = c.blue)
                            }
                        } else {
                            ScAvatar(payerDisplayName, me = payer?.isMe == true, size = AvatarSize.Sm)
                        }
                    },
                )
            }

            // receipt — held locally, uploaded in the background right after the expense is created
            if (receiptsEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Receipt", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("optional", color = c.ink3, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        receipts.forEachIndexed { i, r ->
                            Box(Modifier.size(width = 56.dp, height = 72.dp)) {
                                Box(
                                    Modifier.matchParentSize().clip(RoundedCornerShape(8.dp)).background(c.blueTint).border(1.dp, c.border, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center,
                                ) { ScIcon(if (r.isPdf) ScIcons.Receipt else ScIcons.Image, size = 22.dp, tint = c.blue) }
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp).clip(CircleShape).background(c.surface).border(1.dp, c.borderStrong, CircleShape).clickable { onRemoveReceipt(i) },
                                    contentAlignment = Alignment.Center,
                                ) { ScIcon(ScIcons.Close, size = 11.dp, tint = c.ink2) }
                            }
                        }
                        Column(
                            Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, c.borderStrong, RoundedCornerShape(8.dp)).clickable { showReceiptSource = true },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            ScIcon(ScIcons.Plus, size = 18.dp, tint = c.blue)
                            Text("Add", color = c.blue, fontSize = 11.sp)
                        }
                    }
                    if (receipts.isNotEmpty()) {
                        Text("Uploaded in the background right after you save.", color = c.ink3, fontSize = 12.sp)
                    }
                }
            }

            // participants
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Participants", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                Text("Split", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                ScSegmented(options = SplitMode.labels, selected = split, onSelect = { split = it })
                ScCard(modifier = Modifier.padding(top = 4.dp)) {
                    selectedList.forEachIndexed { i, p ->
                        Row(
                            Modifier.fillMaxWidth().then(if (i > 0) Modifier.topHairline(c.border) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ScAvatar(p.name, me = p.isMe, size = AvatarSize.Sm)
                            Text(p.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            when (mode) {
                                SplitMode.Even -> Text(
                                    moneySubunits(owed[p.userId] ?: 0L, currency),
                                    color = c.ink, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily,
                                )
                                SplitMode.Share -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    UnitStepper(units = shareUnits[p.userId] ?: 1, onChange = { shareUnits = shareUnits + (p.userId to it) })
                                    Text(moneySubunits(owed[p.userId] ?: 0L, currency), color = c.ink3, fontFamily = ShareCostTheme.monoFamily, fontSize = 14.sp)
                                }
                                SplitMode.Percent -> InlineNumberField(
                                    value = percentText[p.userId].orEmpty(),
                                    onValueChange = { percentText = percentText + (p.userId to sanitizeDecimal(it)) },
                                    width = 88.dp, suffix = "%",
                                )
                                SplitMode.Exact -> InlineNumberField(
                                    value = exactText[p.userId].orEmpty(),
                                    onValueChange = { exactText = exactText + (p.userId to sanitizeDecimal(it)) },
                                    width = 104.dp, prefix = symbol,
                                )
                            }
                        }
                    }
                    if (selectedList.isEmpty()) {
                        Text("Add at least one participant", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                    }
                }
                // live math footer for the variable modes
                if (mode == SplitMode.Percent && selectedList.isNotEmpty()) {
                    val ok = percentScaled == 10_000L
                    // What's still unallocated for the % to add up to 100 (negative = over-assigned).
                    val remainingScaled = 10_000L - percentScaled
                    val over = remainingScaled < 0
                    val statusTint = if (ok) c.blue else if (over) c.danger else c.warning
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ScIcon(if (ok) ScIcons.CheckCircle else if (over) ScIcons.Alert else ScIcons.Info, size = 15.dp, tint = statusTint)
                            Text(
                                when {
                                    ok -> "All assigned"
                                    over -> "${format2dp(-remainingScaled / 100.0)}% over"
                                    else -> "${format2dp(remainingScaled / 100.0)}% left to assign"
                                },
                                color = statusTint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (!ok) Text(
                            "Distribute remainder", color = c.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                                percentText = distributeRemainder(ids, percents).mapValues { format2dp(it.value) }
                            }.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (mode == SplitMode.Exact && selectedList.isNotEmpty()) {
                    // diff > 0 → still to hand out; diff < 0 → over the total (a share must come down).
                    val diff = amountSubunits - exactTotal
                    val ok = diff == 0L
                    val over = diff < 0
                    val statusTint = if (ok) c.blue else if (over) c.danger else c.warning
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ScIcon(if (ok) ScIcons.CheckCircle else if (over) ScIcons.Alert else ScIcons.Info, size = 15.dp, tint = statusTint)
                            Text(
                                when {
                                    ok -> "All assigned"
                                    over -> "${moneySubunits(-diff, currency)} over"
                                    else -> "${moneySubunits(diff, currency)} left to assign"
                                },
                                color = statusTint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (!ok) Text(
                            "${moneySubunits(exactTotal, currency)} of ${moneySubunits(amountSubunits, currency)}",
                            color = c.ink3, fontSize = 12.sp, fontFamily = ShareCostTheme.monoFamily,
                        )
                    }
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

    if (showReceiptSource) {
        ScModalScaffold(onDismiss = { showReceiptSource = false }) {
            Text("Add a receipt", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            Text("Attach a photo or PDF — it uploads after you save.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            ReceiptSourceRow(ScIcons.Image, "Photos") { showReceiptSource = false; onPickReceipt(PickSource.Photos) }
            ReceiptSourceRow(ScIcons.Archive, "Files (image or PDF)") { showReceiptSource = false; onPickReceipt(PickSource.Files) }
            ReceiptSourceRow(ScIcons.Camera, "Take a photo") { showReceiptSource = false; onPickReceipt(PickSource.Camera) }
        }
    }

    if (showCurrencyDialog) {
        ScModalScaffold(onDismiss = { showCurrencyDialog = false }) {
            Text("Expense currency", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            listOf("USD", "EUR", "GBP", "MXN", "CAD", "AUD", "JPY", "INR", "KES").forEach { code ->
                Row(
                    Modifier.fillMaxWidth().clickable { currency = code; showCurrencyDialog = false }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(code, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily, modifier = Modifier.width(48.dp))
                    Text(currencySymbol(code), color = c.ink2, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (code == currency) ScIcon(ScIcons.Check, size = 18.dp, tint = c.blue)
                }
            }
        }
    }

    if (showCategoryDialog) {
        ScModalScaffold(onDismiss = { showCategoryDialog = false }) {
            Text("Category", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { cat ->
                    val on = categoryId == cat.key
                    val tint = Color(cat.colorHex)
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.width(152.dp).clip(shape)
                            .background(if (on) tint.copy(alpha = 0.12f) else c.page)
                            .border(if (on) 2.dp else 1.dp, if (on) tint else c.borderStrong, shape)
                            .clickable {
                                categoryId = if (on) null else cat.key
                                showCategoryDialog = false
                            }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ScIcon(CategoryCatalog.icon(cat.iconToken), size = 18.dp, tint = tint)
                        Text(cat.label, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (on) ScIcon(ScIcons.Check, size = 15.dp, tint = tint)
                    }
                }
            }
        }
    }

    if (showPayerDialog) {
        var someoneElse by remember { mutableStateOf(isOutsidePayer) }
        var outsideDraft by remember { mutableStateOf(outsidePayerName ?: "") }
        ScModalScaffold(onDismiss = { showPayerDialog = false }) {
            Text("Who paid?", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            participants.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        payerId = p.userId
                        outsidePayerName = null
                        // A member who paid is part of the split by default — add them (still removable below).
                        selected = selected + p.userId
                        known = known + p.userId
                        showPayerDialog = false
                    }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScAvatar(p.name, me = p.isMe, size = AvatarSize.Sm)
                    Text(p.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (!isOutsidePayer && p.userId == effectivePayerId) ScIcon(ScIcons.Check, size = 18.dp, tint = c.blue)
                }
            }
            Box(Modifier.topHairline(c.border)) {
                Row(
                    Modifier.fillMaxWidth().clickable { someoneElse = true }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                        ScIcon(ScIcons.User, size = 15.dp, tint = c.blue)
                    }
                    Text("Someone else", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (isOutsidePayer && !someoneElse) ScIcon(ScIcons.Check, size = 18.dp, tint = c.blue)
                }
            }
            if (someoneElse) {
                Box(Modifier.padding(top = 4.dp)) {
                    ScField("Their name") { ScTextField(outsideDraft, { outsideDraft = it }, placeholder = "e.g. the Airbnb host") }
                }
                Text(
                    "An outside payer isn't part of the split — everyone owes them their share.",
                    color = c.ink3, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    ScButton("Done", {
                        if (outsideDraft.isNotBlank()) {
                            outsidePayerName = outsideDraft.trim()
                            payerId = ""
                            showPayerDialog = false
                        }
                    }, enabled = outsideDraft.isNotBlank())
                }
            }
        }
    }
}

/** A tappable source row in the "Add a receipt" sheet (Photos / Files / Camera). */
@Composable
private fun ReceiptSourceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = ShareCostTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScIcon(icon, size = 20.dp, tint = c.blue)
        Text(label, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** A compact `[-] n× [+]` integer stepper for SHARE weights; clamps at a minimum of one share. */
@Composable
private fun UnitStepper(units: Int, onChange: (Int) -> Unit) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.height(34.dp).clip(shape).border(1.dp, c.borderStrong, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(ScIcons.Minus, enabled = units > 1) { onChange((units - 1).coerceAtLeast(1)) }
        Text("${units}×", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = ShareCostTheme.monoFamily, textAlign = TextAlign.Center, modifier = Modifier.width(30.dp))
        StepButton(ScIcons.Plus, enabled = true) { onChange(units + 1) }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val c = ShareCostTheme.colors
    Box(
        Modifier.size(32.dp).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { ScIcon(icon, size = 16.dp, tint = if (enabled) c.ink2 else c.ink3) }
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
    val c = ShareCostTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = c.ink, fontSize = 15.sp, fontFamily = ShareCostTheme.monoFamily, textAlign = TextAlign.End),
        cursorBrush = SolidColor(c.blue),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        decorationBox = { inner ->
            Row(
                Modifier.width(width).height(38.dp).clip(shape).background(c.page)
                    .border(if (focused) 2.dp else 1.dp, if (focused) c.blue else c.borderStrong, shape)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                prefix?.let { Text(it, color = c.ink3, fontSize = 15.sp, fontFamily = ShareCostTheme.monoFamily) }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text("0", color = c.ink3, fontSize = 15.sp, fontFamily = ShareCostTheme.monoFamily, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                    inner()
                }
                suffix?.let { Text(it, color = c.ink3, fontSize = 15.sp, fontFamily = ShareCostTheme.monoFamily) }
            }
        },
    )
}

/** Keep only digits and a single decimal point (for the inline %/Exact fields). */
private fun sanitizeDecimal(text: String): String {
    val filtered = text.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    return if (dot < 0) filtered else filtered.substring(0, dot + 1) + filtered.substring(dot + 1).replace(".", "")
}

private fun parsePercent(text: String?): Double {
    val cleaned = text?.trim().orEmpty()
    if (cleaned.isEmpty()) return 0.0
    return cleaned.toDoubleOrNull() ?: 0.0
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
