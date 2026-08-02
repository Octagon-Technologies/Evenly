package app.splitevenly.ui.screen.bill

/**
 * One page being read, reduced to what the scan sheet needs to draw its tile (a photo vs. a PDF). Kept
 * deliberately tiny so the working sheet doesn't hold the raw bytes.
 */
data class ScanPageUi(val isPdf: Boolean)

/**
 * Which scan failure to render — mirrors the non-[Success] cases of `ScanOutcome` so the editor can show
 * a distinct card (offline vs. couldn't-read vs. a retryable error vs. not-configured vs. rate-limited)
 * with the right actions. Manual entry is reachable from every one.
 */
enum class ScanErrorKind { Offline, NoReceiptFound, Unavailable, Error, Blocked }

/** [ScanErrorKind] as the `kind` property on the `scan_failed` analytics event — reuses this taxonomy
 *  rather than inventing a parallel one (`scan_blocked` fires separately for [ScanErrorKind.Blocked]). */
fun ScanErrorKind.analyticsKind(): String = when (this) {
    ScanErrorKind.Offline -> "offline"
    ScanErrorKind.NoReceiptFound -> "no_receipt_found"
    ScanErrorKind.Unavailable -> "unavailable"
    ScanErrorKind.Error -> "error"
    ScanErrorKind.Blocked -> "blocked"
}

/**
 * Rough client-side diff between a scan's original OCR draft and what the user actually saved — the
 * trust metric behind `scan_result_edited`. Compares by position rather than id: a freshly-scanned line
 * always has a null id, so there's nothing else to key on. A line added or removed after the scan counts
 * as changed. Returns (itemsChanged, itemsTotal), where itemsTotal is the scan's own item count.
 */
fun scanEditStats(scanned: List<EditBillItemUi>, saved: List<EditBillItemUi>): Pair<Int, Int> {
    val overlap = minOf(scanned.size, saved.size)
    var changed = kotlin.math.abs(scanned.size - saved.size)
    for (i in 0 until overlap) {
        val o = scanned[i]
        val s = saved[i]
        if (o.label != s.label || o.quantity != s.quantity || o.totalText != s.totalText) changed++
    }
    return changed to scanned.size
}

/**
 * Drives the scan bottom sheet in the bill editor. The route runs the pick → OCR round-trip and moves
 * this state machine; the screen just renders it. [Idle] shows nothing, [Working] the progress sheet,
 * [Failed] the matching error card.
 */
sealed interface ScanUiState {
    data object Idle : ScanUiState

    /** OCR is running; [pages] are the picked files being read (for the tiles + the "N pages" line). */
    data class Working(val pages: List<ScanPageUi>) : ScanUiState

    /** OCR ended without a usable draft; [kind] picks the card copy + which actions make sense. */
    data class Failed(val kind: ScanErrorKind) : ScanUiState
}
