package app.splitevenly.domain.fx

import kotlin.math.roundToLong

/**
 * Convert an integer subunit amount at [rate].
 *
 * Floating point is unavoidable *somewhere* — [FxResult.rate] is a `Double` by design, because a rate is
 * not money — so the rule that matters is where the result stops being a Double, and how often. This is
 * that boundary, and it lives in `domain/` because "what a converted amount rounds to" is a money
 * decision, not a persistence one. It used to sit in `ExpenseRepositoryImpl` with no test (finding R14).
 *
 * **Call it once per figure a person reads, not once per row behind that figure.** Rounding fifteen EUR
 * shares of 333 and summing them is not the same number as summing them and rounding once, and the
 * difference moves with the rate, so the balance on screen would not reconcile with a hand check.
 * `observeBalances` therefore nets each pair in its own currency first and converts the net.
 *
 * Nothing converted is ever stored: this is a read path, and the ledger keeps every amount in the
 * currency it was entered in.
 */
fun convertSubunits(
    subunits: Long,
    rate: Double,
): Long = (subunits * rate).roundToLong()
