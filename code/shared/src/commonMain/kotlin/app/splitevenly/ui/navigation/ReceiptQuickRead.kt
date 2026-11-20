package app.splitevenly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.splitevenly.core.time.localHour
import app.splitevenly.domain.expense.CategoryHistoryEntry
import app.splitevenly.domain.expense.ExpenseWithShares
import app.splitevenly.domain.receipt.ReceiptOcr
import app.splitevenly.domain.receipt.ReceiptOcrFile
import app.splitevenly.domain.receipt.printedHourOf
import app.splitevenly.domain.receipt.suggestBillTitle
import app.splitevenly.platform.PickedFile
import app.splitevenly.ui.screen.expense.ReceiptReadUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Runs the receipt quick read for "Split one amount" and holds its state for the editor. Lives in the route
 * (it needs [ReceiptOcr] and a clock), kept out of `LedgerRoutes.kt` because that file is over its size
 * ceiling. A new read cancels the one in flight, so replacing a receipt never lets the old answer land.
 */
@Stable
class ReceiptQuickRead internal constructor(
    private val ocr: ReceiptOcr,
    private val groupId: String,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf<ReceiptReadUi>(ReceiptReadUi.None)
        private set
    private var job: Job? = null

    fun start(files: List<PickedFile>) {
        job?.cancel()
        if (files.isEmpty()) return
        state = ReceiptReadUi.Reading
        job =
            scope.launch {
                val summary = ocr.summarize(files.map { ReceiptOcrFile(it.bytes, it.mimeType) }, groupId)
                state =
                    if (summary == null) {
                        ReceiptReadUi.Unreadable
                    } else {
                        val title =
                            suggestBillTitle(
                                merchant = summary.merchantName,
                                kind = summary.kind,
                                printedHour = printedHourOf(summary.printedTime),
                                fallbackHour = Clock.System.localHour(),
                                modelTitle = summary.shortTitle,
                            )
                        ReceiptReadUi.Read(summary, title)
                    }
            }
    }

    /** The receipt was removed: forget the read, but leave whatever it already filled in. */
    fun clear() {
        job?.cancel()
        state = ReceiptReadUi.None
    }
}

@Composable
fun rememberReceiptQuickRead(
    ocr: ReceiptOcr,
    groupId: String,
): ReceiptQuickRead {
    val scope = rememberCoroutineScope()
    return remember(ocr, groupId) { ReceiptQuickRead(ocr, groupId, scope) }
}

/** The group's expenses (newest first) as category history: only the ones someone filed under a category. */
fun categoryHistoryOf(expenses: List<ExpenseWithShares>?): List<CategoryHistoryEntry> =
    expenses.orEmpty().mapNotNull { e -> e.expense.categoryId?.let { CategoryHistoryEntry(e.expense.title, it) } }
