package da.chelimo.sharecost.domain.receipt

import da.chelimo.sharecost.core.error.AppResult

/** A structured, EDITABLE draft of a receipt produced by OCR — amounts in integer minor units. */
data class ReceiptDraft(
    val currency: String,
    val items: List<ReceiptDraftItem>,
    val taxSubunits: Long,
    val gratuitySubunits: Long,
    val tipSubunits: Long,
    val discountSubunits: Long,
    val detectedTotalSubunits: Long,
)

data class ReceiptDraftItem(
    val label: String,
    val quantity: Int,
    val unitPriceSubunits: Long,
)

/**
 * Turns a receipt photo into a structured draft (the `extract-receipt` edge function → Claude vision).
 * The result is only ever a *first draft* the user edits before any money is computed. Returns
 * `Ok(null)` when OCR is unavailable (Supabase not configured, the function inert, or a read failure),
 * so the caller cleanly falls back to manual entry.
 */
interface ReceiptOcr {
    suspend fun extract(bytes: ByteArray, mimeType: String): AppResult<ReceiptDraft?>
}
