package da.chelimo.sharecost.domain.fx

/**
 * Outcome of an FX lookup (03 §6.2). All rates are stored as `USD -> quote`; a cross-pair rate is
 * `usdToTo / usdToFrom`. The lookup walks Room live rates → the build-time baked snapshot → nothing.
 */
sealed interface FxResult {
    /** `from == to`; conversion is identity (rate 1.0). */
    data object Same : FxResult

    /** A rate from synced/fetched `fx_rates`. [asOfDate] is the rate_date actually used; [stale] per §6.3. */
    data class Live(val rate: Double, val asOfDate: String, val stale: Boolean) : FxResult

    /** Build-time baked-snapshot fallback (§6.4). [stale] when the snapshot is older than 7 days. */
    data class Baked(val rate: Double, val snapshotDate: String, val stale: Boolean) : FxResult

    /** No rate available for one or both legs — the caller shows the original currency unconverted. */
    data object Unavailable : FxResult
}

/** The conversion factor to apply, or null when [FxResult.Unavailable]. [FxResult.Same] is 1.0. */
fun FxResult.rateOrNull(): Double? = when (this) {
    FxResult.Same -> 1.0
    is FxResult.Live -> rate
    is FxResult.Baked -> rate
    FxResult.Unavailable -> null
}

/** Whether to surface the "Rate as of <date>" staleness banner (03 §6.3). */
val FxResult.isStale: Boolean
    get() = when (this) {
        is FxResult.Live -> stale
        is FxResult.Baked -> stale
        FxResult.Same, FxResult.Unavailable -> false
    }
