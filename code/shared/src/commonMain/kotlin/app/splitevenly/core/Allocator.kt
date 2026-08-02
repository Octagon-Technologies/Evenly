package app.splitevenly

import app.splitevenly.core.id.UserId

/**
 * Largest-remainder allocator (03-business-rules.md §1.2, D-28).
 *
 * Sorts internally — callers pass weights in any order and get the same result.
 * Tiebreak on equal fractional remainders: UserId.value ascending (UUIDv7 is
 * time-ordered, so smallest value = longest-tenured member).
 */
fun allocate(totalSubunits: Long, weights: List<Pair<UserId, Long>>): Map<UserId, Long> {
    require(weights.isNotEmpty())
    val totalWeight = weights.sumOf { it.second }
    require(totalWeight > 0)

    data class Entry(val id: UserId, val base: Long, val frac: Long)

    val entries = weights.map { (id, w) ->
        Entry(id, totalSubunits * w / totalWeight, (totalSubunits * w).rem(totalWeight))
    }
    val remainder = totalSubunits - entries.sumOf { it.base }

    val sorted = entries.sortedWith(
        compareByDescending<Entry> { it.frac }.thenBy { it.id.value }
    )

    return sorted.mapIndexed { i, e ->
        e.id to (e.base + if (i < remainder) 1L else 0L)
    }.toMap()
}
