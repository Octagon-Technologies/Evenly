package app.splitevenly.ui.screen.bill

/**
 * One page being read, reduced to what the scan sheet needs to draw its tile (a photo vs. a PDF). Kept
 * deliberately tiny so the working sheet doesn't hold the raw bytes.
 */
data class ScanPageUi(val isPdf: Boolean)

/**
 * Which scan failure to render — mirrors the non-[Success] cases of `ScanOutcome` so the editor can show
 * a distinct card (offline vs. couldn't-read vs. a retryable error vs. not-configured) with the right
 * actions. Manual entry is reachable from every one.
 */
enum class ScanErrorKind { Offline, NoReceiptFound, Unavailable, Error }

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
