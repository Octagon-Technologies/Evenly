package da.chelimo.sharecost.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.core.time.todayUtc
import da.chelimo.sharecost.domain.auth.AuthSession
import da.chelimo.sharecost.domain.expense.BillExtrasInput
import da.chelimo.sharecost.domain.expense.BillView
import da.chelimo.sharecost.domain.expense.EditBill
import da.chelimo.sharecost.domain.expense.EditBillItem
import da.chelimo.sharecost.domain.expense.NewBill
import da.chelimo.sharecost.domain.expense.NewBillItem
import da.chelimo.sharecost.domain.expense.TipSplitMode
import da.chelimo.sharecost.domain.receipt.ReceiptDraft
import da.chelimo.sharecost.domain.receipt.ReceiptOcr
import da.chelimo.sharecost.domain.repository.BillRepository
import da.chelimo.sharecost.domain.repository.GroupRepository
import da.chelimo.sharecost.platform.FilePicker
import da.chelimo.sharecost.platform.PickKind
import da.chelimo.sharecost.platform.PickSource
import da.chelimo.sharecost.ui.components.ScCard
import da.chelimo.sharecost.ui.components.ScSheetScaffold
import da.chelimo.sharecost.ui.components.icon.ScIcon
import da.chelimo.sharecost.ui.components.icon.ScIcons
import da.chelimo.sharecost.ui.screen.bill.BillClaimScreen
import da.chelimo.sharecost.ui.screen.bill.BillEditScreen
import da.chelimo.sharecost.ui.screen.bill.ClaimBillState
import da.chelimo.sharecost.ui.screen.bill.ClaimItemUi
import da.chelimo.sharecost.ui.screen.bill.EditBillItemUi
import da.chelimo.sharecost.ui.screen.bill.EditBillState
import da.chelimo.sharecost.ui.screen.bill.priceToSubunits
import da.chelimo.sharecost.ui.screen.expense.format2dp
import da.chelimo.sharecost.ui.theme.ShareCostTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private fun subunitsToText(subunits: Long): String = if (subunits == 0L) "" else format2dp(subunits / 100.0)

/** The FAB chooser: "Split the bill" vs the ordinary even/custom expense. */
@Composable
fun NewExpenseChoiceRoute(groupId: String, onDismiss: () -> Unit, onEven: () -> Unit, onSplitBill: () -> Unit) {
    ScSheetScaffold(onDismiss = onDismiss, title = "Add an expense") {
        ChoiceCard(
            icon = ScIcons.Receipt,
            title = "Split the bill",
            subtitle = "Everyone picks what they had",
            featured = true,
            onClick = onSplitBill,
        )
        Box(Modifier.size(10.dp))
        ChoiceCard(
            icon = ScIcons.Users,
            title = "Split evenly or custom",
            subtitle = "Rent, groceries, a shared cost",
            featured = false,
            onClick = onEven,
        )
        Box(Modifier.size(8.dp))
    }
}

@Composable
private fun ChoiceCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, featured: Boolean, onClick: () -> Unit) {
    val c = ShareCostTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (featured) c.blueTint else c.page)
            .then(if (featured) Modifier else Modifier.padding(0.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(c.page), contentAlignment = Alignment.Center) {
            ScIcon(icon, size = 20.dp, tint = c.blue)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = c.ink2, fontSize = 13.sp)
        }
        ScIcon(ScIcons.ChevR, size = 18.dp, tint = c.ink3)
    }
}

/** Create or edit a bill (the menu + extras). On create, lands on the live claim screen. */
@OptIn(ExperimentalTime::class)
@Composable
fun BillEditRoute(groupId: String, expenseId: String?, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val filePicker = koinInject<FilePicker>()
    val ocr = koinInject<ReceiptOcr>()
    val gid = remember(groupId) { GroupId(groupId) }
    val group by remember(gid) { groups.observeGroup(gid) }.collectAsStateWithLifecycle(null)
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val currency = group?.baseCurrency ?: "USD"
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<EditBillState?>(null) }

    val existing = if (expenseId != null) {
        remember(expenseId) { bills.observeBill(ExpenseId(expenseId)) }.collectAsStateWithLifecycle(null).value
    } else null

    // For edit, wait until the bill loads so the editor's initial state is correct.
    if (expenseId != null && existing == null) return

    key(scanned) {
    BillEditScreen(
        editing = expenseId != null,
        initial = scanned ?: existing?.toEditState(),
        currencyCode = currency,
        saving = saving,
        scanning = scanning,
        onBack = onBack,
        onScanReceipt = {
            scope.launch {
                scanning = true
                val picked = filePicker.pick(PickSource.Camera, PickKind.Image)
                val file = (picked as? AppResult.Ok)?.value?.firstOrNull()
                val draft = file?.let { (ocr.extract(it.bytes, it.mimeType) as? AppResult.Ok)?.value }
                if (draft != null) scanned = draft.toEditState()
                scanning = false
            }
        },
        onSave = { submit ->
            val me = userId ?: return@BillEditScreen
            saving = true
            scope.launch {
                val extras = BillExtrasInput(
                    taxSubunits = submit.taxSubunits,
                    gratuitySubunits = submit.gratuitySubunits,
                    tipSubunits = submit.tipSubunits,
                    tipSplitMode = if (submit.tipEven) TipSplitMode.EVEN else TipSplitMode.PROPORTIONAL,
                    discountSubunits = submit.discountSubunits,
                )
                if (expenseId == null) {
                    val result = bills.createBill(
                        NewBill(
                            groupId = gid,
                            title = submit.title,
                            currency = currency,
                            expenseDate = Clock.System.todayUtc(),
                            payerUserId = me,
                            createdBy = me,
                            items = submit.items.map { NewBillItem(it.label.trim(), it.quantity, priceToSubunits(it.priceText)) },
                            extras = extras,
                        ),
                    )
                    when (result) {
                        is AppResult.Ok -> onCreated(result.value.value)
                        is AppResult.Err -> saving = false
                    }
                } else {
                    bills.editBill(
                        ExpenseId(expenseId),
                        EditBill(
                            title = submit.title,
                            expenseDate = existing?.expense?.expenseDate ?: Clock.System.todayUtc(),
                            payerUserId = existing?.expense?.payerUserId ?: me,
                            items = submit.items.map { EditBillItem(it.id, it.label.trim(), it.quantity, priceToSubunits(it.priceText)) },
                            extras = extras,
                            editedBy = me,
                        ),
                    )
                    onBack()
                }
            }
        },
    )
    }
}

/** The live claim screen — everyone taps what they had; the tab + shares derive in real time. */
@Composable
fun BillClaimRoute(groupId: String, expenseId: String, onBack: () -> Unit, onEditBill: () -> Unit, onAskGroup: () -> Unit) {
    val bills = koinInject<BillRepository>()
    val groups = koinInject<GroupRepository>()
    val auth = koinInject<AuthSession>()
    val gid = remember(groupId) { GroupId(groupId) }
    val eid = remember(expenseId) { ExpenseId(expenseId) }
    val bill by remember(eid) { bills.observeBill(eid) }.collectAsStateWithLifecycle(null)
    val members by remember(gid) { groups.observeMembers(gid) }.collectAsStateWithLifecycle(emptyList())
    val userId by auth.currentUserId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val view = bill ?: return
    val me = userId
    val nameByUser = members.associate { it.userId.value to (it.displayName ?: "Someone") }
    val claimsByItem = view.claims.groupBy { it.itemId }

    val items = view.items.map { item ->
        val claims = claimsByItem[item.id].orEmpty()
        val mine = claims.filter { it.userId == me }.sumOf { it.quantity }
        val others = claims.filter { it.userId != me }
        ClaimItemUi(
            id = item.id,
            label = item.label,
            quantity = item.quantity,
            unitPriceSubunits = item.unitPriceSubunits,
            myQuantity = mine,
            othersQuantity = others.sumOf { it.quantity },
            otherNames = others.mapNotNull { nameByUser[it.userId.value] }.distinct(),
        )
    }
    val state = ClaimBillState(
        title = view.expense.title,
        currency = view.expense.currency,
        yourTabSubunits = me?.let { view.tabByUser[it] } ?: 0L,
        totalSubunits = view.expense.amountSubunits,
        claimedSubunits = view.tabByUser.values.sum(),
        items = items,
        livePeople = view.claims.map { it.userId }.distinct().size,
    )

    BillClaimScreen(
        state = state,
        onBack = onBack,
        onSetClaim = { itemId, qty ->
            val who = me ?: return@BillClaimScreen
            scope.launch { bills.setClaim(eid, itemId, UserId(who.value), qty) }
        },
        onEditBill = onEditBill,
        onAskGroup = onAskGroup,
        onFinish = onBack,
    )
}

private fun ReceiptDraft.toEditState(): EditBillState = EditBillState(
    title = "",
    items = items.map { EditBillItemUi(null, it.label, it.quantity, subunitsToText(it.unitPriceSubunits)) }
        .ifEmpty { listOf(EditBillItemUi(null, "", 1, "")) },
    taxText = subunitsToText(taxSubunits),
    gratuityText = subunitsToText(gratuitySubunits),
    tipText = subunitsToText(tipSubunits),
    tipEven = true,
    discountText = subunitsToText(discountSubunits),
)

private fun BillView.toEditState(): EditBillState = EditBillState(
    title = expense.title,
    items = items.map { EditBillItemUi(it.id, it.label, it.quantity, subunitsToText(it.unitPriceSubunits)) }
        .ifEmpty { listOf(EditBillItemUi(null, "", 1, "")) },
    taxText = subunitsToText(extras.taxSubunits),
    gratuityText = subunitsToText(extras.gratuitySubunits),
    tipText = subunitsToText(extras.tipSubunits),
    tipEven = extras.tipSplitMode == TipSplitMode.EVEN,
    discountText = subunitsToText(extras.discountSubunits),
)
