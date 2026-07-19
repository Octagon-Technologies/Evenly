package da.chelimo.sharecost.ui.screen.expense

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import da.chelimo.sharecost.ui.components.AvatarSize
import da.chelimo.sharecost.ui.components.ChipVariant
import da.chelimo.sharecost.ui.components.ScAvatar
import da.chelimo.sharecost.ui.components.ScButton
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScCheck
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
import da.chelimo.sharecost.ui.screen.bill.EditBillItemUi
import da.chelimo.sharecost.ui.screen.bill.EditBillState
import da.chelimo.sharecost.ui.screen.bill.EditBillSubmit
import da.chelimo.sharecost.ui.screen.bill.ExtrasCard
import da.chelimo.sharecost.ui.screen.bill.ItemEditorRow
import da.chelimo.sharecost.ui.screen.bill.ScanErrorSheet
import da.chelimo.sharecost.ui.screen.bill.ScanProgressSheet
import da.chelimo.sharecost.ui.screen.bill.ScanSourceRow
import da.chelimo.sharecost.ui.screen.bill.ScanUiState
import da.chelimo.sharecost.ui.screen.bill.editBillItemUi
import da.chelimo.sharecost.ui.screen.bill.priceToSubunits
import da.chelimo.sharecost.ui.screen.group.CategoryCatalog
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** Which way the creator is splitting: divide one total, or itemize (claim by item). */
enum class SplitApproach { Divide, ByItem }

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
    // Who was on the group's most recent expense — defaults a brand-new expense's participant selection
    // to "whoever was actually there last time" instead of the whole group. Ignored when [prefill] is set
    // (editing always wins) or when nobody in it is a current participant (a fresh/first expense).
    lastExpenseParticipantIds: Set<String> = emptySet(),
    receipts: List<PickedReceiptUi> = emptyList(),
    receiptsEnabled: Boolean = false,
    // ── itemized ("By what each had") body — only used when creating (editing keeps its single mode) ──
    // The scan pipeline is driven by the route: [scanState] shows progress/errors, [scanned] delivers a
    // completed draft that pre-fills the item list, and the on* callbacks pick/cancel/retry the scan.
    scanState: ScanUiState = ScanUiState.Idle,
    scanned: EditBillState? = null,
    attachedReceiptCount: Int = 0,
    onBack: () -> Unit = {},
    onSave: (AddExpenseSubmit) -> Unit = {},
    onSaveItemized: (EditBillSubmit) -> Unit = {},
    onScanReceipt: (PickSource) -> Unit = {},
    onCancelScan: () -> Unit = {},
    onRetryScan: () -> Unit = {},
    onDismissScan: () -> Unit = {},
    onAddPlaceholder: (String) -> Unit = {},
    onPickReceipt: (PickSource) -> Unit = {},
    onRemoveReceipt: (Int) -> Unit = {},
) {
    val c = ShareCostTheme.colors
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
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
    // Default selection: editing keeps its saved shares; a new expense defaults to whoever was on the
    // group's last expense (filtered to people still around); with no such signal, everyone's selected.
    var selected by remember {
        mutableStateOf(
            prefill?.selectedUserIds
                ?: lastExpenseParticipantIds.filterTo(HashSet()) { id -> participants.any { it.userId == id } }
                    .takeIf { it.isNotEmpty() }
                ?: participants.map { it.userId }.toSet(),
        )
    }
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

    // Which body is showing. The choice is an up-front question (below): null = not yet chosen on a new
    // expense (show the chooser); an edit skips it and stays in its (divide) body.
    var splitApproach by remember { mutableStateOf<SplitApproach?>(if (editing) SplitApproach.Divide else null) }
    // "By what each had" body state — a typed-or-scanned item list plus the bill-level extras.
    var items by remember { mutableStateOf(listOf(editBillItemUi(null, "", 1, 0L))) }
    var taxText by remember { mutableStateOf("") }
    var gratuityText by remember { mutableStateOf("") }
    var tipText by remember { mutableStateOf("") }
    var discountText by remember { mutableStateOf("") }
    var showScanSource by remember { mutableStateOf(false) }
    // A completed scan pre-fills the items + extras (never the shared name/participants) and lands you in
    // the itemized body — a fresh [scanned] instance each time, so a re-scan replaces the list.
    LaunchedEffect(scanned) {
        scanned?.let { s ->
            items = s.items
            taxText = s.taxText
            gratuityText = s.gratuityText
            tipText = s.tipText
            discountText = s.discountText
            splitApproach = SplitApproach.ByItem
        }
    }

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

    // ── itemized body derived values ──
    val isItemized = splitApproach == SplitApproach.ByItem
    // A restaurant bill is always "Food & Drink" — no point asking, so the Category field is hidden
    // for this path and the category is set for the user.
    LaunchedEffect(isItemized) { if (isItemized) categoryId = "food" }
    val itemSubtotal = items.sumOf { priceToSubunits(it.totalText) } // each line's total is the truth
    val itemTotal = itemSubtotal + priceToSubunits(taxText) + priceToSubunits(gratuityText) +
        priceToSubunits(tipText) - priceToSubunits(discountText)
    val hasItem = items.any { it.label.trim().isNotEmpty() }

    val divideValid = amountSubunits > 0 && title.isNotBlank() && selected.isNotEmpty() && splitValid
    val itemizedValid = title.isNotBlank() && hasItem
    val isValid = if (isItemized) itemizedValid else divideValid

    // The Save action, shared by the top bar and the button under the totals (A4). Stays live so we can
    // validate on tap and reveal the gaps instead of leaving a dead, greyed button.
    val submit = fun() {
        if (!isValid) {
            showErrors = true
            scope.launch { scrollState.animateScrollTo(0) }
            return
        }
        if (isItemized) {
            onSaveItemized(
                EditBillSubmit(
                    title = title.trim(),
                    items = items.filter { it.label.trim().isNotEmpty() },
                    taxSubunits = priceToSubunits(taxText),
                    gratuitySubunits = priceToSubunits(gratuityText),
                    tipSubunits = priceToSubunits(tipText),
                    discountSubunits = priceToSubunits(discountText),
                    participantIds = selected,
                    payerUserId = if (isOutsidePayer) null else effectivePayerId,
                ),
            )
        } else onSave(
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
    }

    // Up-front split-type question: a focused editor beats a toggle you can flip by accident. Until it's
    // answered on a new expense, show the chooser; Back from the editor returns here (see navIcon below).
    if (splitApproach == null) {
        Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
            ScTopBar(title = "New expense", navIcon = { ScIconButton(ScIcons.Close, onBack) })
            SplitApproachChooser(onChoose = { splitApproach = it })
        }
        return
    }

    Column(Modifier.fillMaxSize().background(c.page).systemBarsPadding()) {
        ScTopBar(
            title = if (editing) "Edit expense" else if (isItemized) "Restaurant bill" else "Split one amount",
            navIcon = { ScIconButton(if (editing) ScIcons.Close else ScIcons.Back, { if (editing) onBack() else splitApproach = null }) },
            actions = {
                // Button stays live; validate on tap and reveal the gaps rather than sitting dead + greyed.
                val active = !saving
                val bg = if (active) c.blue else c.blueTint2
                val fg = if (active) c.onAccent else c.disabledInk
                Box(
                    Modifier.clip(RoundedCornerShape(11.dp)).background(bg)
                        .then(if (active) Modifier.clickable { submit() } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(if (saving) "Saving…" else "Save", color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            },
        )
        Column(
            Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
                .verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── shared header: title, category, paid by, participants — entered once, both modes ──
            ScField("Title") {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ScTextField(title, { title = it }, placeholder = "What was it for?", isError = showErrors && title.isBlank())
                    if (showErrors && title.isBlank()) {
                        Text("Give it a title", color = c.danger, fontSize = 12.sp)
                    }
                }
            }

            // Who's splitting this — asked FIRST, before "Paid by": pick the people, THEN the payer. Testers
            // reached for the payer before adding anyone, so the payer picker misread as "who's in the split".
            ParticipantsField(
                participants = participants,
                selected = selected,
                onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                onSelectAll = { selected = participants.map { it.userId }.toSet() },
                onDeselectAll = { selected = emptySet() },
                onAddClick = { showAddDialog = true },
            )

            // category (F2, optional) + paid by — side by side to keep the editor compact. Each is still
            // its own tappable picker row (like before), just half-width now. A restaurant bill skips
            // Category entirely (auto-set to Food & Drink above), so Paid by gets the full row to itself.
            val selectedCategory = categories.firstOrNull { it.key == categoryId }
            val paidByField: @Composable RowScope.() -> Unit = {
                ScField("Paid by", modifier = Modifier.weight(1f)) {
                    ScSelectField(
                        payerDisplayName,
                        { showPayerDialog = true },
                        leading = {
                            if (isOutsidePayer) {
                                Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                                    ScIcon(ScIcons.User, size = 15.dp, tint = c.blueText)
                                }
                            } else {
                                ScAvatar(payerDisplayName, me = payer?.isMe == true, size = AvatarSize.Sm)
                            }
                        },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isItemized) {
                    ScField("Category", modifier = Modifier.weight(1f)) {
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
                                        ScIcon(ScIcons.Tag, size = 15.dp, tint = c.blueText)
                                    }
                                }
                            },
                        )
                    }
                }
                paidByField()
            }

            // receipt — held locally, uploaded in the background right after the expense is created. Only
            // in the divide flow; the itemized body attaches the pages you scan instead.
            if (receiptsEnabled && !isItemized) {
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
                                ) { ScIcon(if (r.isPdf) ScIcons.Receipt else ScIcons.Image, size = 22.dp, tint = c.blueText) }
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
                            ScIcon(ScIcons.Plus, size = 18.dp, tint = c.blueText)
                            Text("Add", color = c.blueText, fontSize = 11.sp)
                        }
                    }
                    if (receipts.isNotEmpty()) {
                        Text("Uploaded in the background right after you save.", color = c.ink3, fontSize = 12.sp)
                    }
                }
            }

            // ── the split body (the divide-vs-itemize choice was made up front, so no in-editor toggle) ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isItemized) {
                    // ── By what each had: a typed-or-scanned item list, bill extras, derived total ──
                    // Scan is the marquee action — a hero card at the top, but it recedes: a soft blue-toward-
                    // black wash (blueTint), distinct from the page yet not the prominent grey slab it was.
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.blueTint)
                            .border(1.dp, c.border, RoundedCornerShape(16.dp)).clickable { showScanSource = true }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.blue), contentAlignment = Alignment.Center) {
                            ScIcon(ScIcons.Camera, size = 22.dp, tint = c.onAccent)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Scan the receipt", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("Snap a photo or PDF and we'll fill in the items.", color = c.ink2, fontSize = 12.sp)
                        }
                        ScIcon(ScIcons.ChevR, size = 18.dp, tint = c.blueText)
                    }
                    if (attachedReceiptCount > 0) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.blueTint).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ScIcon(ScIcons.Receipt, size = 16.dp, tint = c.blueText)
                            Text(
                                if (attachedReceiptCount == 1) "Receipt attached, saves with the bill"
                                else "$attachedReceiptCount receipt pages attached, save with the bill",
                                color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    // A quiet "or add items by hand" divider under the scan hero, then the item list.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).height(1.dp).background(c.border))
                        Text("or add items by hand", color = c.ink3, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Box(Modifier.weight(1f).height(1.dp).background(c.border))
                    }
                    Text("Items", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    ScCard {
                        items.forEachIndexed { i, item ->
                            ItemEditorRow(
                                item = item,
                                symbol = symbol,
                                showDivider = i > 0,
                                onChange = { updated -> items = items.toMutableList().also { it[i] = updated } },
                                onRemove = { items = items.filterIndexed { idx, _ -> idx != i } },
                            )
                        }
                    }
                    // Add item sits below the item cards — that's where a new one actually lands.
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { items = items + editBillItemUi(null, "", 1, 0L) }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ScIcon(ScIcons.Plus, size = 15.dp, tint = c.blueText)
                        Text("Add item", color = c.blueText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (showErrors && !hasItem) {
                        Text("Add at least one item with a name", color = c.danger, fontSize = 12.sp)
                    }
                    ExtrasCard(
                        symbol = symbol,
                        subtotalSubunits = itemSubtotal,
                        totalSubunits = itemTotal,
                        currencyCode = currency,
                        taxText = taxText, onTax = { taxText = it },
                        gratuityText = gratuityText, onGratuity = { gratuityText = it },
                        tipText = tipText, onTip = { tipText = it },
                        discountText = discountText, onDiscount = { discountText = it },
                    )
                    // Save right under the total — where you look when you're done (A4).
                    ScButton(if (saving) "Saving…" else "Save & assign items", { submit() }, enabled = !saving)
                } else {
                // ── Divide the total: the amount to split, the method, and the per-person preview ──
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
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
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
                Text("How to split", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                            "Distribute remainder", color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
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
                // Save right under the per-person split — where you look when you're done (A4).
                ScButton(if (saving) "Saving…" else "Save expense", { submit() }, enabled = !saving)
                } // ── end Divide body ──
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
            Text("Attach a photo or PDF. It uploads after you save.", color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
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
                    if (code == currency) ScIcon(ScIcons.Check, size = 18.dp, tint = c.blueText)
                }
            }
        }
    }

    if (showCategoryDialog) {
        ScModalScaffold(onDismiss = { showCategoryDialog = false }) {
            Text("Category", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            // A 2-per-row grid of equal-width chips (not a fixed 152dp width) so two always fit
            // side by side regardless of the sheet's width, instead of collapsing to one long column.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { cat ->
                            val on = categoryId == cat.key
                            val tint = Color(cat.colorHex)
                            val shape = RoundedCornerShape(12.dp)
                            Row(
                                Modifier.weight(1f).clip(shape)
                                    .background(if (on) tint.copy(alpha = 0.12f) else c.page)
                                    .border(if (on) 2.dp else 1.dp, if (on) tint else c.borderStrong, shape)
                                    .clickable {
                                        categoryId = if (on) null else cat.key
                                        showCategoryDialog = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ScIcon(CategoryCatalog.icon(cat.iconToken), size = 18.dp, tint = tint)
                                Text(cat.label, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (on) ScIcon(ScIcons.Check, size = 15.dp, tint = tint)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
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
                    if (!isOutsidePayer && p.userId == effectivePayerId) ScIcon(ScIcons.Check, size = 18.dp, tint = c.blueText)
                }
            }
            // Add a brand-new person right here (creates a placeholder member) — a member who paid must
            // exist first, and this covers "the payer isn't in the group yet".
            Box(Modifier.topHairline(c.border)) {
                Row(
                    Modifier.fillMaxWidth().clickable { showPayerDialog = false; showAddDialog = true }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                        ScIcon(ScIcons.Plus, size = 15.dp, tint = c.blueText)
                    }
                    Text("Add someone new", color = c.blueText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
            }
            // An outside payer (not a group member, not in the split) — clearer than the old "Someone else",
            // which testers read as "another member".
            Box(Modifier.topHairline(c.border)) {
                Row(
                    Modifier.fillMaxWidth().clickable { someoneElse = true }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                        ScIcon(ScIcons.User, size = 15.dp, tint = c.blueText)
                    }
                    Text("Someone outside the group", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (isOutsidePayer && !someoneElse) ScIcon(ScIcons.Check, size = 18.dp, tint = c.blueText)
                }
            }
            if (someoneElse) {
                Box(Modifier.padding(top = 4.dp)) {
                    ScField("Their name") { ScTextField(outsideDraft, { outsideDraft = it }, placeholder = "e.g. the Airbnb host") }
                }
                Text(
                    "An outside payer isn't part of the split, everyone owes them their share.",
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

    // ── itemized scan sheets: source picker + progress/error, driven by the route's scan state ──
    if (showScanSource) {
        ScModalScaffold(onDismiss = { showScanSource = false }) {
            Text("Scan the bill", color = c.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "A restaurant check or store receipt with line items. We'll pull them out for you, several pages read as one bill.",
                color = c.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp),
            )
            ScanSourceRow(ScIcons.Image, "Photos") { showScanSource = false; onScanReceipt(PickSource.Photos) }
            ScanSourceRow(ScIcons.Archive, "Files (image or PDF)") { showScanSource = false; onScanReceipt(PickSource.Files) }
            ScanSourceRow(ScIcons.Camera, "Take a photo") { showScanSource = false; onScanReceipt(PickSource.Camera) }
        }
    }
    when (val s = scanState) {
        is ScanUiState.Working -> ScanProgressSheet(pages = s.pages, onCancel = onCancelScan)
        is ScanUiState.Failed -> ScanErrorSheet(
            kind = s.kind,
            onManual = onDismissScan,
            onRetry = onRetryScan,
            onPickAgain = { onDismissScan(); showScanSource = true },
        )
        ScanUiState.Idle -> {}
    }
}

/**
 * The up-front split-type question, shown before the editor: "Divide the total" vs "By what each had".
 * Choosing one opens a focused editor for that mode — no in-editor toggle to flip by accident. Itemize
 * sits a level *above* Even/Shares/%/Exact on purpose: those divide a known total; this derives the total
 * from items, so it isn't a peer of them.
 */
@Composable
private fun SplitApproachChooser(onChoose: (SplitApproach) -> Unit) {
    val c = ShareCostTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("How are you splitting this?", color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        }
        ApproachChoiceCard(
            icon = ScIcons.Wallet,
            title = "Split one amount",
            subtitle = "One total that you can split evenly, by percentages, or by typing everyone's exact share.",
            onClick = { onChoose(SplitApproach.Divide) },
        )
        ApproachChoiceCard(
            icon = ScIcons.Food,
            title = "Splitting a restaurant bill",
            subtitle = "Scan the receipt or list items, everyone pays for what they had.",
            onClick = { onChoose(SplitApproach.ByItem) },
        )
    }
}

@Composable
private fun ApproachChoiceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.page).border(1.dp, c.borderStrong, shape)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
            ScIcon(icon, size = 24.dp, tint = c.blueText)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = c.ink2, fontSize = 13.sp, lineHeight = 17.sp)
        }
        ScIcon(ScIcons.ChevR, size = 18.dp, tint = c.ink3)
    }
}

/** Small groups render inline; past this count the list collapses into an expandable row (below). */
private const val PARTICIPANTS_INLINE_THRESHOLD = 6

/**
 * "Participants" — inline toggle chips for a small group (unchanged; it already reads well at that size).
 * Past [PARTICIPANTS_INLINE_THRESHOLD] members that same chip grid gets long, so it collapses to a single
 * summary row ("5 of 15 selected" — a count, not names, to stay compact) that expands in place into a
 * checkbox list, mirroring the collapsed-row language already used for Category/Paid by above.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParticipantsField(
    participants: List<AddParticipantUi>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onAddClick: () -> Unit,
) {
    val c = ShareCostTheme.colors
    if (participants.size <= PARTICIPANTS_INLINE_THRESHOLD) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Who's splitting this?", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (participants.size <= 1) {
                Text("Add the people splitting this bill.", color = c.ink3, fontSize = 12.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                participants.forEach { p ->
                    val on = p.userId in selected
                    ScParticipantChip(
                        p.name, selected = on,
                        leading = { ScAvatar(p.name, me = p.isMe, size = AvatarSize.Xs) },
                        trailing = if (on) ({ ScIcon(ScIcons.Check, size = 14.dp) }) else null,
                        onClick = { onToggle(p.userId) },
                    )
                }
                ScParticipantChip("Add", selected = false, leading = { ScIcon(ScIcons.Plus, size = 15.dp, tint = c.blueText) }, onClick = onAddClick)
            }
        }
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val count = selected.size
    val allOn = count >= participants.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Who's splitting this?", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val rowShape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(rowShape).background(c.page)
                .border(1.dp, c.borderStrong, rowShape).clickable { expanded = !expanded }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(99.dp)).background(c.blueTint), contentAlignment = Alignment.Center) {
                ScIcon(ScIcons.Users, size = 15.dp, tint = c.blueText)
            }
            Text("$count of ${participants.size} selected", color = c.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
            ScIcon(if (expanded) ScIcons.ChevU else ScIcons.ChevD, size = 16.dp, tint = c.ink3)
        }
        if (expanded) {
            ScCard {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (allOn) "Deselect all" else "Select all",
                            color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable { if (allOn) onDeselectAll() else onSelectAll() }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                        Box(Modifier.weight(1f))
                        Text(
                            "Add", color = c.blueText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAddClick).padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        participants.forEachIndexed { i, p ->
                            val on = p.userId in selected
                            Row(
                                Modifier.fillMaxWidth().then(if (i > 0) Modifier.topHairline(c.border) else Modifier)
                                    .clickable { onToggle(p.userId) }.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ScAvatar(p.name, me = p.isMe, size = AvatarSize.Xs)
                                Text(p.name, color = c.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                ScCheck(checked = on, onCheckedChange = { onToggle(p.userId) })
                            }
                        }
                    }
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
        ScIcon(icon, size = 20.dp, tint = c.blueText)
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
