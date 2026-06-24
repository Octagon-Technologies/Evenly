package da.chelimo.sharecost.ui.screen.expense

import da.chelimo.sharecost.allocate
import da.chelimo.sharecost.core.id.UserId
import kotlin.math.roundToLong

/**
 * The four ways an expense can be split (design/src/screens-addexpense.jsx). [label] is the segmented-
 * control caption; [wire] is the value persisted in `expense.split_mode` (and read back by the detail
 * screen). The order matches the design's segmented control.
 */
enum class SplitMode(val label: String, val wire: String) {
    Even("Even", "EVEN"),
    Share("Share", "SHARE"),
    Percent("%", "PERCENT"),
    Exact("Exact", "EXACT");

    companion object {
        fun fromLabel(label: String): SplitMode = entries.firstOrNull { it.label == label } ?: Even
        fun fromWire(wire: String): SplitMode = entries.firstOrNull { it.wire == wire } ?: Even
        val labels: List<String> get() = entries.map { it.label }
    }
}

/**
 * Initial values for the add-expense screen when re-opening an existing expense to edit (the route
 * builds this from `ExpenseWithShares`). The maps only carry entries for the active [mode]; the screen
 * seeds its own state from them so the editor re-renders the exact split the user last saved.
 */
data class AddExpensePrefill(
    val amountSubunits: Long,
    val title: String,
    val payerUserId: String,
    val selectedUserIds: Set<String>,
    val mode: SplitMode,
    val shareUnits: Map<String, Int> = emptyMap(),
    val percentText: Map<String, String> = emptyMap(),
    val exactText: Map<String, String> = emptyMap(),
    val categoryId: String? = null,
    /** Non-null when the expense was paid by someone outside the group (no [payerUserId]). */
    val payerOutsideName: String? = null,
)

/**
 * The owed-subunits map for one expense, the single source of truth shared by the live preview (the
 * add-expense screen) and persistence (the route) — so what's displayed is exactly what's saved.
 *
 * EVEN/SHARE/PERCENT delegate to the largest-remainder [allocate] (D-28) so the result always sums to
 * [totalSubunits]; EXACT is a pass-through of the user's amounts (the screen gates Save until they sum
 * to the total, AC-INV-001). Returns an empty map when there's nothing to split.
 */
fun splitOwed(
    mode: SplitMode,
    totalSubunits: Long,
    ids: List<String>,
    units: Map<String, Int> = emptyMap(),
    percents: Map<String, Double> = emptyMap(),
    exact: Map<String, Long> = emptyMap(),
): Map<String, Long> {
    if (ids.isEmpty() || totalSubunits <= 0) return emptyMap()
    return when (mode) {
        SplitMode.Even -> alloc(totalSubunits, ids.map { it to 1L })
        SplitMode.Share -> {
            val weights = ids.map { it to (units[it] ?: 1).coerceAtLeast(0).toLong() }
            if (weights.sumOf { it.second } <= 0L) emptyMap() else alloc(totalSubunits, weights)
        }
        SplitMode.Percent -> {
            // 4-decimal precision: percentage × 10_000 = integer weight (AC-M2-004).
            val weights = ids.map { it to ((percents[it] ?: 0.0) * 10_000).roundToLong() }
            if (weights.sumOf { it.second } <= 0L) emptyMap() else alloc(totalSubunits, weights)
        }
        SplitMode.Exact -> ids.associateWith { (exact[it] ?: 0L).coerceAtLeast(0L) }
    }
}

private fun alloc(total: Long, weights: List<Pair<String, Long>>): Map<String, Long> =
    allocate(total, weights.map { (id, w) -> UserId(id) to w }).mapKeys { it.key.value }

/** One participant's computed owed amount plus the raw input that produced it (for re-rendering). */
data class SplitShareInput(
    val userId: String,
    val owedSubunits: Long,
    val units: Int? = null,
    val percent: Double? = null,
    val exactSubunits: Long? = null,
)

/**
 * Everything the add-expense screen emits on Save; the route maps it straight onto `NewExpense`.
 * When [payerOutsideName] is non-null the expense was paid by someone outside the group: the route
 * persists `payerUserId = null` + that name, and the outside payer is not part of the split.
 */
data class AddExpenseSubmit(
    val amountSubunits: Long,
    val title: String,
    val payerUserId: String,
    val mode: SplitMode,
    val shares: List<SplitShareInput>,
    val currency: String,
    val categoryId: String? = null,
    val payerOutsideName: String? = null,
)

/** Sum of typed percentages in hundredths-of-a-percent (so an exact 100% reads as `10_000`). */
fun percentTotalScaled(ids: List<String>, percents: Map<String, Double>): Long =
    ids.sumOf { ((percents[it] ?: 0.0) * 100).roundToLong() }

/** Sum of the typed exact amounts (subunits). */
fun exactTotalSubunits(ids: List<String>, exact: Map<String, Long>): Long =
    ids.sumOf { exact[it] ?: 0L }

/**
 * Adjusts [percents] so the [ids] sum to exactly 100.00%, returning the new map. A shortfall is spread
 * across the participants still at 0% (or everyone if none are zero); an overshoot is rebalanced across
 * everyone. Works in hundredths so the result is exact to two decimals. Also seeds an even split when
 * called with no prior percentages.
 */
fun distributeRemainder(ids: List<String>, percents: Map<String, Double>): Map<String, Double> {
    if (ids.isEmpty()) return percents
    val scaled = ids.associateWith { ((percents[it] ?: 0.0) * 100).roundToLong() }.toMutableMap()
    val remainder = 10_000L - scaled.values.sum()
    if (remainder == 0L) return scaled.mapValues { it.value / 100.0 }

    val targets = if (remainder > 0) ids.filter { scaled[it] == 0L }.ifEmpty { ids } else ids
    val per = remainder / targets.size
    var leftover = remainder - per * targets.size // same sign as remainder, |leftover| < targets.size
    targets.forEach { id ->
        var add = per
        if (leftover != 0L) {
            val step = if (leftover > 0) 1L else -1L
            add += step
            leftover -= step
        }
        scaled[id] = (scaled.getValue(id) + add).coerceAtLeast(0L)
    }
    return scaled.mapValues { it.value / 100.0 }
}

/** Two-decimal formatter (no platform `String.format` in commonMain). For non-negative values. */
fun format2dp(value: Double): String {
    val scaled = (value * 100).roundToLong()
    val whole = scaled / 100
    val frac = (scaled % 100).let { if (it < 0) -it else it }
    return "$whole.${if (frac < 10) "0$frac" else "$frac"}"
}
