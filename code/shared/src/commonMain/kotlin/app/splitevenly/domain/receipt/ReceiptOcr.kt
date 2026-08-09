package app.splitevenly.domain.receipt

/** A structured, EDITABLE draft of a receipt produced by OCR — amounts in integer minor units. */
data class ReceiptDraft(
    val currency: String,
    val items: List<ReceiptDraftItem>,
    val taxSubunits: Long,
    val gratuitySubunits: Long,
    val tipSubunits: Long,
    val discountSubunits: Long,
    // Printed charges with no other slot (delivery fee, bottle deposit, card surcharge). Before this
    // existed the extractor folded them into gratuity to keep the bill's total honest, which mislabelled
    // the row the user then saw.
    val otherChargesSubunits: Long = 0,
    val detectedTotalSubunits: Long,
    // False when no tier produced a draft whose sum (computed server-side, never by the model) matched
    // the receipt's printed total — the draft shown is still the best available guess
    // (never a dead end), but the caller should flag it rather than treat it as a quiet success.
    // Defaults true for manual-entry / non-OCR construction paths, where "verification" doesn't apply.
    val verified: Boolean = true,
)

data class ReceiptDraftItem(
    val label: String,
    val quantity: Int,
    val lineTotalSubunits: Long,
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
    /** OCR produced an editable draft. [scanId] is the server's id for this scan, when it returns one
     *  (Plan A) — nullable so a client ahead of that deploy still works; attach as an analytics property
     *  only when present. */
    data class Success(val draft: ReceiptDraft, val scanId: String? = null) : ScanOutcome

    /** The extract ran but nothing usable came back (blurry, not a receipt, empty item list). */
    data object NoReceiptFound : ScanOutcome

    /** The device has no connection — we never attempted the upload. */
    data object Offline : ScanOutcome

    /** OCR isn't wired up (Supabase / the extract function isn't configured). */
    data object Unavailable : ScanOutcome

    /** A network or server error while extracting — a retry may succeed. */
    data class Failed(val message: String? = null) : ScanOutcome

    /** The server turned the request away without attempting the vision call — the per-user rate limit
     *  (429) today, any other explicit refusal later. Distinct from [Failed]: nothing went wrong, the
     *  caller just isn't allowed to spend another call right now. */
    data class Blocked(val reason: String) : ScanOutcome
}

/**
 * Turns a receipt photo (or several images / a PDF) into a structured draft (the `extract-receipt` edge
 * function → Claude vision). All [files] are read together as ONE bill, so a multi-page receipt yields a
 * single item list. The result is only ever a *first draft* the user edits before any money is computed.
 * The implementation pre-checks connectivity and never throws — every failure is a typed [ScanOutcome] so
 * the caller can guide the user (offline, couldn't-read, retryable error) instead of failing silently.
 */
interface ReceiptOcr {
    /** [groupId] rides along in the request so server-side analytics can attribute the scan to a group
     *  (Plan A); harmless if the server ignores it, and omitted from the body when null. */
    suspend fun extract(files: List<ReceiptOcrFile>, groupId: String? = null): ScanOutcome
}
