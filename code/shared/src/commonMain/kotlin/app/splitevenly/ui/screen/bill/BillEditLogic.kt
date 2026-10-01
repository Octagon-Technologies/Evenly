package app.splitevenly.ui.screen.bill

/** An item row with both a name and a non-zero price: the only kind auto-save may write. */
fun EditBillItemUi.isComplete(): Boolean = label.trim().isNotEmpty() && priceToSubunits(totalText) > 0L

/** A row the user never touched (no name, no price). Dropped silently, since nothing is lost. */
fun EditBillItemUi.isUntouched(): Boolean = label.trim().isEmpty() && priceToSubunits(totalText) == 0L

/** Rows that are half-filled (name without price, or price without name). These block a silent Back. */
fun incompleteItemCount(items: List<EditBillItemUi>): Int = items.count { !it.isComplete() && !it.isUntouched() }

/** Items auto-save will write; incomplete rows stay local in the editor. */
fun savableItems(items: List<EditBillItemUi>): List<EditBillItemUi> = items.filter { it.isComplete() }

/** Auto-save runs only for a named bill with at least one complete item. */
fun canAutoSave(title: String, items: List<EditBillItemUi>): Boolean = title.trim().isNotEmpty() && savableItems(items).isNotEmpty()

/** One-line Back notice for [count] incomplete rows, or null when there is nothing to warn about. */
fun incompleteItemsNotice(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 item has no name or price. Fix it or remove it."
    else -> "$count items have no name or price. Fix them or remove them."
}

/** How a re-scan lands on a bill that already has items. */
enum class ScanMergeMode { ADD, REPLACE }

/** An extras field whose second-scan value differs from what the bill has; the user confirms each. */
enum class ExtraField { TAX, GRATUITY, OTHER_CHARGES, TIP, DISCOUNT }

data class ExtraSuggestion(val field: ExtraField, val currentText: String, val scannedText: String)

data class ScanMergeResult(
    val state: EditBillState,
    /** Empty for [ScanMergeMode.REPLACE]. For ADD, extras are never summed, only suggested. */
    val suggestions: List<ExtraSuggestion>,
)

/**
 * Applies a completed scan to the editor state. ADD appends the scanned items and keeps the current
 * extras: a second partial photo usually repeats the totals block, so summing would double-count.
 * Differing scanned extras come back as [ExtraSuggestion]s instead. REPLACE is the old behaviour.
 */
fun mergeScan(current: EditBillState, scanned: EditBillState, mode: ScanMergeMode): ScanMergeResult {
    if (mode == ScanMergeMode.REPLACE || current.items.none { it.isComplete() }) {
        return ScanMergeResult(
            state = current.copy(
                items = scanned.items,
                taxText = scanned.taxText,
                gratuityText = scanned.gratuityText,
                otherChargesText = scanned.otherChargesText,
                tipText = scanned.tipText,
                discountText = scanned.discountText,
                verified = scanned.verified,
            ),
            suggestions = emptyList(),
        )
    }
    val kept = current.items.filterNot { it.isUntouched() }
    val pairs = listOf(
        Triple(ExtraField.TAX, current.taxText, scanned.taxText),
        Triple(ExtraField.GRATUITY, current.gratuityText, scanned.gratuityText),
        Triple(ExtraField.OTHER_CHARGES, current.otherChargesText, scanned.otherChargesText),
        Triple(ExtraField.TIP, current.tipText, scanned.tipText),
        Triple(ExtraField.DISCOUNT, current.discountText, scanned.discountText),
    )
    val suggestions = pairs.mapNotNull { (f, cur, sc) ->
        if (priceToSubunits(sc) != 0L && priceToSubunits(sc) != priceToSubunits(cur)) ExtraSuggestion(f, cur, sc) else null
    }
    return ScanMergeResult(
        state = current.copy(items = kept + scanned.items, verified = current.verified && scanned.verified),
        suggestions = suggestions,
    )
}

/** Applies one confirmed suggestion. */
fun EditBillState.withExtra(s: ExtraSuggestion): EditBillState = when (s.field) {
    ExtraField.TAX -> copy(taxText = s.scannedText)
    ExtraField.GRATUITY -> copy(gratuityText = s.scannedText)
    ExtraField.OTHER_CHARGES -> copy(otherChargesText = s.scannedText)
    ExtraField.TIP -> copy(tipText = s.scannedText)
    ExtraField.DISCOUNT -> copy(discountText = s.scannedText)
}
