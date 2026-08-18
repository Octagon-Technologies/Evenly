package app.splitevenly.domain.export

/**
 * The outcome of exporting a group's ledger. A closed set so the screen can say the true thing rather
 * than one generic failure, mirroring `ScanOutcome`.
 */
sealed interface ExportOutcome {
    /** The CSV text, ready to hand to the OS share sheet. */
    data class Success(val csv: String) : ExportOutcome

    /**
     * The group holds no live Pro pass. A refusal, not a failure: nothing went wrong and retrying
     * changes nothing, so the screen must never offer a retry for this one.
     */
    data object NeedsPro : ExportOutcome

    /** No connection. The export is a server-side render of server-side data, so there is no offline
     *  version of it to fall back to. */
    data object Offline : ExportOutcome

    /** Supabase (or the export function) isn't configured, as in the fully offline build. */
    data object Unavailable : ExportOutcome

    /** A network or server error. A retry may work. */
    data class Failed(val message: String? = null) : ExportOutcome
}

/** Renders a group's whole ledger as CSV. Server-side by necessity: it reads every member's rows. */
interface GroupExporter {
    suspend fun exportCsv(groupId: String): ExportOutcome
}
