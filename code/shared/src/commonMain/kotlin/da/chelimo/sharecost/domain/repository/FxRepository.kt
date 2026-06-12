package da.chelimo.sharecost.domain.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.domain.fx.FxResult

/**
 * Foreign-exchange rates (06 §3.1, 03 §6). Unlike the other repositories this one genuinely reaches
 * the network — the public Frankfurter API (04 §5), which needs no auth — and caches into Room. The
 * lookup chain never fails loudly: it degrades to the baked snapshot and finally to
 * [FxResult.Unavailable] (03 §6.4).
 */
interface FxRepository {

    /**
     * Convert [from] → [to] as valued on [asOf] (an ISO-8601 date, e.g. the expense date). Uses the
     * most recent rate on or before [asOf]; the result carries the staleness signal (03 §6.3).
     */
    suspend fun rate(from: String, to: String, asOf: String): FxResult

    /**
     * Cold-start refresh (03 §6.1, 04 §5.2): if the newest cached rate predates today (UTC), fetch
     * the latest rates and upsert them. Best-effort — a network failure is swallowed and reported as
     * success-with-no-op so the caller never surfaces an error (03 §6.1/§6.4).
     */
    suspend fun refreshIfStale(): AppResult<Unit>
}
