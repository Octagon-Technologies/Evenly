package da.chelimo.sharecost.domain.expense

import da.chelimo.sharecost.allocate
import da.chelimo.sharecost.core.id.UserId

fun itemizedShares(
    subtotals: List<Pair<UserId, Long>>,
    taxSubunits: Long,
    tipSubunits: Long,
    tipSplitMode: TipSplitMode
): Map<UserId, Long> {
    val taxShares: Map<UserId, Long> = if (taxSubunits == 0L) emptyMap()
    else allocate(taxSubunits, subtotals)

    val tipShares: Map<UserId, Long> = if (tipSubunits == 0L) emptyMap()
    else when (tipSplitMode) {
        TipSplitMode.PROPORTIONAL -> allocate(tipSubunits, subtotals)
        TipSplitMode.EVEN -> allocate(tipSubunits, subtotals.map { it.first to 1L })
    }

    return subtotals.associate { (id, subtotal) ->
        id to subtotal + (taxShares[id] ?: 0L) + (tipShares[id] ?: 0L)
    }
}
