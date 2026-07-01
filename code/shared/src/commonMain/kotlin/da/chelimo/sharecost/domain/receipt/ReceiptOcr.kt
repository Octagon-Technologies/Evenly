package da.chelimo.sharecost.domain.receipt

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

/** One page of a receipt to OCR — a photo or a PDF. Several ride together as one multi-page bill. */
data class ReceiptOcrFile(
    val bytes: ByteArray,
    val mimeType: String,
)

/**
 * The outcome of a scan — a closed set the UI can render distinctly, so "you're offline", "we couldn't
 * read it", and "something went wrong" are three different messages instead of the same silent no-op.
 * The manual-entry path is always reachable from every non-[Success] state.
 */
sealed interface ScanOutcome {
    /** OCR produced an editable draft. */
    data class Success(val draft: ReceiptDraft) : ScanOutcome

    /** The extract ran but nothing usable came back (blurry, not a receipt, empty item list). */
    data object NoReceiptFound : ScanOutcome

    /** The device has no connection — we never attempted the upload. */
    data object Offline : ScanOutcome

    /** OCR isn't wired up (Supabase / the extract function isn't configured). */
    data object Unavailable : ScanOutcome

    /** A network or server error while extracting — a retry may succeed. */
    data class Failed(val message: String? = null) : ScanOutcome
}

/**
 * Turns a receipt photo (or several images / a PDF) into a structured draft (the `extract-receipt` edge
 * function → Claude vision). All [files] are read together as ONE bill, so a multi-page receipt yields a
 * single item list. The result is only ever a *first draft* the user edits before any money is computed.
 * The implementation pre-checks connectivity and never throws — every failure is a typed [ScanOutcome] so
 * the caller can guide the user (offline, couldn't-read, retryable error) instead of failing silently.
 */
interface ReceiptOcr {
    suspend fun extract(files: List<ReceiptOcrFile>): ScanOutcome
}
