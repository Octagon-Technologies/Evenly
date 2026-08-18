package app.splitevenly.domain.expense

import app.splitevenly.core.id.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BillSplitTest {
    private val A = UserId("a")
    private val B = UserId("b")
    private val C = UserId("c")
    private val D = UserId("d")
    private val E = UserId("e")
    private val F = UserId("f")

    private fun indiv(vararg c: IndividualClaim) = c.toList()

    private fun shared(vararg s: SharedMember) = s.toList()

    private fun statusOf(
        r: BillResult,
        itemId: String,
    ) = r.items.first { it.itemId == itemId }.status

    // The engine's truth is the LINE TOTAL, but these scenarios read more clearly as a per-unit price;
    // this helper builds the line total (unit × quantity) so the expected owed values stay obvious.
    private fun item(
        itemId: String,
        unitPriceSubunits: Long,
        quantity: Int,
    ) = BillItem(itemId, unitPriceSubunits * quantity, quantity)

    // Individual whole units: each claimed unit costs exactly its price; fully claimed → RESOLVED.
    @Test
    fun individualUnits_exact_andResolved() {
        val items = listOf(item("pizza", 1800L, quantity = 4))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("pizza", A, 2), IndividualClaim("pizza", B, 1), IndividualClaim("pizza", C, 1)),
                shared(),
                BillExtras(),
            )
        assertEquals(mapOf(A to 3600L, B to 1800L, C to 1800L), r.owedByUser)
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "pizza"))
        assertTrue(r.fullyResolved)
    }

    // Units left over with nobody sharing → UNCLAIMED (the bill stays unresolved, nagging).
    @Test
    fun leftoverUnits_noSharers_unclaimed() {
        val items = listOf(item("pizza", 1000L, quantity = 4))
        val r = splitBill(items, indiv(IndividualClaim("pizza", A, 2)), shared(), BillExtras())
        assertEquals(mapOf(A to 2000L), r.owedByUser) // only the 2 claimed units
        assertEquals(ItemStatus.UNCLAIMED, statusOf(r, "pizza"))
        assertEquals(1, r.unclaimedCount)
        assertTrue(!r.fullyResolved)
    }

    // The shared set absorbs the leftover units, split evenly → RESOLVED, line fully covered.
    @Test
    fun sharedSet_absorbsLeftover() {
        val items = listOf(item("pizza", 1000L, quantity = 4))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("pizza", A, 2)),
                shared(SharedMember("pizza", B), SharedMember("pizza", C)), // 2 leftover units ÷ 2
                BillExtras(),
            )
        assertEquals(mapOf(A to 2000L, B to 1000L, C to 1000L), r.owedByUser)
        assertEquals(4000L, r.owedByUser.values.sum()) // whole line covered
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "pizza"))
    }

    // Per-item "who pays what" (the assign screen's rows): 2 fish @ $24, A had one whole plate and split
    // the second with B → A $36, B $12. perItemByUser is penny-exact and, with no extras, sums to owed.
    @Test
    fun perItemByUser_splitsALineByPerson() {
        val items = listOf(item("fish", 2400L, quantity = 2))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("fish", A, 1)),
                shared(SharedMember("fish", A), SharedMember("fish", B)),
                BillExtras(),
            )
        assertEquals(mapOf(A to 3600L, B to 1200L), r.perItemByUser["fish"])
        assertEquals(r.owedByUser, r.perItemByUser["fish"]) // no extras → per-item sums to the tab
    }

    // PORTIONS — the 8-nacho case that the single-share-set model can't express: 8 nachos @ $4, Andrew 2
    // solo, Bob 3 solo, {Bob, Mary} split 1, 2 still unassigned. (A=Andrew, B=Bob, C=Mary.)
    @Test
    fun portions_mixedCountsPlusSharedSlice_andLeftover() {
        val items = listOf(item("nachos", 400L, quantity = 8))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("nachos", A, 2), IndividualClaim("nachos", B, 3)),
                shared(),
                BillExtras(),
                sharedPortions = listOf(SharedPortion("nachos", "p1", quantity = 1, members = listOf(B, C))),
            )
        // Andrew 2×$4=$8; Bob 3×$4=$12 + half of the shared $4 = $2 → $14; Mary $2.
        assertEquals(mapOf(A to 800L, B to 1400L, C to 200L), r.owedByUser)
        assertEquals(mapOf(A to 800L, B to 1400L, C to 200L), r.perItemByUser["nachos"])
        assertEquals(2400L, r.owedByUser.values.sum()) // $24 of $32 assigned…
        assertEquals(ItemStatus.UNCLAIMED, statusOf(r, "nachos")) // …2 orders still need someone
    }

    // A multi-count line fully covered by several distinct shared slices → RESOLVED, penny-exact.
    @Test
    fun portions_multipleSharedSlices_resolve() {
        val items = listOf(item("wings", 300L, quantity = 4)) // 4 × $3 = $12
        val r =
            splitBill(
                items,
                indiv(),
                shared(),
                BillExtras(),
                sharedPortions =
                    listOf(
                        SharedPortion("wings", "p1", quantity = 2, members = listOf(A, B)), // $6 ÷ 2 = $3 each
                        SharedPortion("wings", "p2", quantity = 2, members = listOf(C, D)), // $6 ÷ 2 = $3 each
                    ),
            )
        assertEquals(mapOf(A to 300L, B to 300L, C to 300L, D to 300L), r.owedByUser)
        assertEquals(1200L, r.owedByUser.values.sum())
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "wings"))
    }

    // Shared by everyone (case 4): the whole line splits across the set. Auto-union = it's just a set.
    @Test
    fun sharedByEveryone_wholeLine() {
        val items = listOf(item("naan", 500L, quantity = 3)) // 3 × $5 = $15
        val r = splitBill(items, indiv(), shared(SharedMember("naan", A), SharedMember("naan", B), SharedMember("naan", C)), BillExtras())
        assertEquals(mapOf(A to 500L, B to 500L, C to 500L), r.owedByUser) // $15 ÷ 3
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "naan"))
    }

    // The real Veda case: 4 Makhani, 3 people take one each, the 4th is shared by all 6.
    @Test
    fun mixedIndividualAndShared_theNotesMakhani() {
        val items = listOf(item("makhani", 2400L, quantity = 4)) // $24 each
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("makhani", A, 1), IndividualClaim("makhani", B, 1), IndividualClaim("makhani", C, 1)),
                shared(*listOf(A, B, C, D, E, F).map { SharedMember("makhani", it) }.toTypedArray()), // 4th ÷ 6
                BillExtras(),
            )
        assertEquals(2800L, r.owedByUser[A]) // $24 own + $4 share of the 4th
        assertEquals(400L, r.owedByUser[D]) // just $4 share
        assertEquals(9600L, r.owedByUser.values.sum()) // whole $96 line
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "makhani"))
    }

    // Indivisible shared split stays penny-exact via largest-remainder.
    @Test
    fun sharedSplit_indivisible_isPennyExact() {
        val items = listOf(item("nachos", 1000L, quantity = 1))
        val r =
            splitBill(items, indiv(), shared(SharedMember("nachos", A), SharedMember("nachos", B), SharedMember("nachos", C)), BillExtras())
        assertEquals(1000L, r.owedByUser.values.sum())
        assertEquals(listOf(334L, 333L, 333L), r.owedByUser.values.sortedDescending())
    }

    // Line total is the truth: a $10.00 line over 3 individually-claimed units splits penny-exact, no
    // leak, even though 1000 ÷ 3 isn't whole (largest-remainder gives 334/333/333, summing to $10.00).
    @Test
    fun lineTotalIndivisibleByUnits_isPennyExact() {
        val items = listOf(BillItem("dosa", 1000L, quantity = 3)) // $10.00 total for 3 (NOT per-unit)
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("dosa", A, 1), IndividualClaim("dosa", B, 1), IndividualClaim("dosa", C, 1)),
                shared(),
                BillExtras(),
            )
        assertEquals(1000L, r.owedByUser.values.sum()) // exactly $10 — nothing lost to rounding
        assertEquals(listOf(334L, 333L, 333L), r.owedByUser.values.sortedDescending())
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "dosa"))
    }

    // Over-claim (more individual units than ordered) → OVERCLAIMED flag; still costs every claimed unit.
    @Test
    fun overClaim_flagged() {
        val items = listOf(item("beer", 900L, quantity = 2))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("beer", A, 1), IndividualClaim("beer", B, 1), IndividualClaim("beer", C, 1)),
                shared(),
                BillExtras(),
            )
        assertEquals(2700L, r.owedByUser.values.sum())
        assertEquals(ItemStatus.OVERCLAIMED, statusOf(r, "beer"))
        assertEquals(1, r.overClaimedCount)
    }

    // A single unit that was individually claimed AND then shared is treated as fully shared: the whole
    // line splits across the set ($5 ÷ 2 = $2.50), never $0.00 from a zero "leftover". Stray claim ignored.
    @Test
    fun singleUnit_claimedThenShared_isFullyShared() {
        val items = listOf(item("juice", 500L, 1))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("juice", A, 1)), // A had checked it…
                shared(SharedMember("juice", A), SharedMember("juice", B)), // …then split it with B
                BillExtras(),
            )
        assertEquals(mapOf(A to 250L, B to 250L), r.owedByUser) // $5 ÷ 2, not A=$5 / B=$0
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "juice"))
    }

    // Tax + gratuity proportional, tip even (the default).
    @Test
    fun taxGratuityProportional_tipEven() {
        val items = listOf(item("a-meal", 6000L, 1), item("b-meal", 4000L, 1))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("a-meal", A, 1), IndividualClaim("b-meal", B, 1)),
                shared(),
                BillExtras(taxSubunits = 800L, gratuitySubunits = 1200L, tipSubunits = 1000L, tipSplitMode = TipSplitMode.EVEN),
            )
        assertEquals(6000L + 1200L + 500L, r.owedByUser[A]) // 7700
        assertEquals(4000L + 800L + 500L, r.owedByUser[B]) // 5300
        assertEquals(13000L, r.owedByUser.values.sum())
    }

    // Other charges (delivery fee, bottle deposit, card surcharge) ride exactly as tax does. Added with the
    // column (2026-08-08): before it existed the receipt scanner folded these into gratuity to keep the
    // bill's total honest, so the amount was right and the label on the user's screen was a lie.
    @Test
    fun otherCharges_proportional_likeTax() {
        val items = listOf(item("a-meal", 6000L, 1), item("b-meal", 2000L, 1))
        val claims = indiv(IndividualClaim("a-meal", A, 1), IndividualClaim("b-meal", B, 1))
        val r = splitBill(items, claims, shared(), BillExtras(otherChargesSubunits = 500L))
        // A ordered 3/4 of the food, so A carries 3/4 of the fee. Nothing may leak: the parts sum to 8500.
        assertEquals(6000L + 375L, r.owedByUser[A])
        assertEquals(2000L + 125L, r.owedByUser[B])
        assertEquals(8500L, r.owedByUser.values.sum())

        // It lands in the same proportional bucket as tax and gratuity, which is what TabBreakdown folds.
        val withTax = splitBill(items, claims, shared(), BillExtras(taxSubunits = 400L, otherChargesSubunits = 500L))
        assertEquals(675L, withTax.breakdownByUser[A]?.taxSubunits)
        assertEquals(225L, withTax.breakdownByUser[B]?.taxSubunits)
        assertEquals(8900L, withTax.owedByUser.values.sum())
    }

    // Tip proportional toggle + discount, both proportional and penny-exact.
    @Test
    fun tipProportional_andDiscount() {
        val items = listOf(item("a-meal", 6000L, 1), item("b-meal", 4000L, 1))
        val claims = indiv(IndividualClaim("a-meal", A, 1), IndividualClaim("b-meal", B, 1))
        val tip = splitBill(items, claims, shared(), BillExtras(tipSubunits = 2000L, tipSplitMode = TipSplitMode.PROPORTIONAL))
        assertEquals(7200L, tip.owedByUser[A])
        assertEquals(4800L, tip.owedByUser[B])
        val disc = splitBill(items, claims, shared(), BillExtras(discountSubunits = 1000L))
        assertEquals(5400L, disc.owedByUser[A])
        assertEquals(3600L, disc.owedByUser[B])
    }

    // Regression (the $5-juice-shows-$102 bug): an early/sole claimant must NOT absorb the whole bill's
    // tax/gratuity/tip. Extras ride proportional to the FULL bill subtotal; the unclaimed remainder stays
    // unbilled until those items are claimed. Full subtotal $256 ($5 juice + $251 mains); A claims only the
    // juice. A pays $5 + a proportional sliver of the $97.60 extras — ~$6.91 — never the whole $102.60.
    @Test
    fun earlyClaimant_doesNotAbsorbAllExtras() {
        val items = listOf(item("juice", 500L, 1), item("mains", 25100L, 1))
        val extras =
            BillExtras(taxSubunits = 2068L, gratuitySubunits = 5292L, tipSubunits = 2400L, tipSplitMode = TipSplitMode.PROPORTIONAL)
        val r = splitBill(items, indiv(IndividualClaim("juice", A, 1)), shared(), extras, participants = listOf(A, B, C))
        assertEquals(691L, r.owedByUser[A]) // $5.00 + $1.44 tax/grat + $0.47 tip — not $102.60
    }

    // An EVEN tip splits per head across the bill's participants (stable), not just whoever's claimed so
    // far: with 3 participants and only A claiming, A owes 1/3 of the tip, not all of it.
    @Test
    fun evenTip_perParticipant_notJustClaimants() {
        val items = listOf(item("a", 1000L, 1), item("b", 1000L, 1))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("a", A, 1)),
                shared(),
                BillExtras(tipSubunits = 3000L, tipSplitMode = TipSplitMode.EVEN),
                participants = listOf(A, B, C),
            )
        assertEquals(2000L, r.owedByUser[A]) // $10 item + $10 tip (30 ÷ 3), not $10 + $30
    }

    // The per-person breakdown (food/tax/tip) the claim screen shows: parts sum to the tab, and the tax
    // part folds gratuity in.
    @Test
    fun breakdown_partsSumToTab_taxFoldsGratuity() {
        val items = listOf(item("a", 6000L, 1), item("b", 4000L, 1))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("a", A, 1), IndividualClaim("b", B, 1)),
                shared(),
                BillExtras(taxSubunits = 800L, gratuitySubunits = 1200L, tipSubunits = 1000L, tipSplitMode = TipSplitMode.EVEN),
                participants = listOf(A, B),
            )
        val a = r.breakdownByUser[A]!!
        assertEquals(6000L, a.itemsSubunits)
        assertEquals(1200L, a.taxSubunits) // (800 tax + 1200 gratuity) × 6000/10000, folded
        assertEquals(500L, a.tipSubunits) // 1000 ÷ 2 participants
        assertEquals(r.owedByUser[A], a.totalSubunits) // parts reconcile to the tab
        assertEquals(7700L, a.totalSubunits)
    }

    // Nothing claimed → empty tab, every line UNCLAIMED, never a divide-by-zero on a proportional extra.
    @Test
    fun nothingClaimed_empty_allUnclaimed() {
        val items = listOf(item("x", 1000L, 1), item("y", 500L, 2))
        val r = splitBill(items, indiv(), shared(), BillExtras(taxSubunits = 100L))
        assertTrue(r.owedByUser.isEmpty())
        assertEquals(2, r.unclaimedCount)
        assertTrue(!r.fullyResolved)
    }

    // --- the engine's preconditions (review/findings-domain.md D1, D4) ----------------------------
    // Both of these hand someone a share BELOW zero, and `ShareDao`'s `remaining_subunits > 0` filters
    // then erase the credit — so the payer is shown owed MORE than the bill. Neither is reachable from
    // the app's own editor (`validate` rejects both), and both are reachable from sync and the web
    // guest path, which is why the engine has to state the rule itself.

    // D1: a discount rides proportional to the item subtotal, so it may not exceed it. $26 of food, a
    // $9 tip and a $30 voucher: the tip carries the bill total to $5, so the total check passes, and
    // Bob (the only claimant) used to be handed −$1.00 while the payer read as owed $6.00 on a $5 bill.
    @Test
    fun discountExceedsSubtotal_noNegativeShare() {
        val items = listOf(BillItem("dinner", 2600L, quantity = 1))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("dinner", B, 1)),
                shared(),
                BillExtras(tipSubunits = 900L, tipSplitMode = TipSplitMode.EVEN, discountSubunits = 3000L),
                participants = listOf(A, B, C),
            )
        assertTrue(r.owedByUser.values.all { it >= 0L }, "no one may hold a negative tab: ${r.owedByUser}")
        // The voucher is spent down to the food it applies to ($26), leaving the $9 tip to split.
        assertEquals(mapOf(A to 300L, B to 300L, C to 300L), r.owedByUser)
        assertEquals(900L, r.owedByUser.values.sum())
    }

    // D4: a negative line total makes `splitEven` leak a subunit (−1000 over 3 units → −999) and hands
    // its claimants shares below zero. Costed at 0, the split stays exact and every tab stays positive.
    @Test
    fun negativeLineTotal_isNeutralizedNotMisSplit() {
        val items = listOf(BillItem("credit", -500L, quantity = 3), BillItem("mains", 5000L, quantity = 1))
        val r =
            splitBill(
                items,
                indiv(
                    IndividualClaim("credit", A, 1),
                    IndividualClaim("credit", B, 1),
                    IndividualClaim("credit", C, 1),
                    IndividualClaim("mains", A, 1),
                ),
                shared(),
                BillExtras(),
            )
        assertTrue(r.owedByUser.values.all { it >= 0L }, "no one may hold a negative tab: ${r.owedByUser}")
        assertEquals(mapOf(A to 5000L, B to 0L, C to 0L), r.owedByUser)
        assertEquals(5000L, r.owedByUser.values.sum()) // not 5002 — nothing materializes out of nothing
        assertEquals(mapOf(A to 0L, B to 0L, C to 0L), r.perItemByUser["credit"])
    }

    // --- "finished" is a statement about units, not money (D2, D5) --------------------------------

    // D2: every line is comped (line total 0) and Alice claimed both, so the bill IS finished and the
    // $30 tip is real money. `fullyClaimed` used to require `fullSubtotal > 0`, so Bob's and Carol's
    // even slices were computed and then dropped — a finished bill summing to a third of its own tip.
    @Test
    fun compedBill_evenTipStillSplitsAcrossParticipants() {
        val items = listOf(BillItem("comped-a", 0L, quantity = 1), BillItem("comped-b", 0L, quantity = 1))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("comped-a", A, 1), IndividualClaim("comped-b", A, 1)),
                shared(),
                BillExtras(tipSubunits = 3000L, tipSplitMode = TipSplitMode.EVEN),
                participants = listOf(A, B, C),
            )
        assertEquals(mapOf(A to 1000L, B to 1000L, C to 1000L), r.owedByUser)
        assertEquals(3000L, r.owedByUser.values.sum())
        assertTrue(r.fullyResolved)
    }

    // D2, second half: the same comped bill with a discount. Nothing is discountable — the discount is
    // proportional to an item subtotal of zero — so it must not fall back to "split it among whoever
    // claimed", which put 100% of a table-wide voucher on the one person who claimed and took her
    // below zero.
    @Test
    fun compedBill_withDiscount_conservesAndStaysNonNegative() {
        val items = listOf(BillItem("comped-a", 0L, quantity = 1), BillItem("comped-b", 0L, quantity = 1))
        val r =
            splitBill(
                items,
                indiv(IndividualClaim("comped-a", A, 1), IndividualClaim("comped-b", A, 1)),
                shared(),
                BillExtras(tipSubunits = 500L, tipSplitMode = TipSplitMode.EVEN, discountSubunits = 300L),
                participants = listOf(A, B, C),
            )
        assertTrue(r.owedByUser.values.all { it >= 0L }, "no one may hold a negative tab: ${r.owedByUser}")
        assertEquals(mapOf(A to 167L, B to 167L, C to 166L), r.owedByUser)
        assertEquals(500L, r.owedByUser.values.sum())
    }

    // D5: a priced line with no units is claimable by nobody — `splitEven` returns no slices, so its
    // $25 is charged to no one. Reading RESOLVED made a bill the UI called finished silently $25 short
    // and silenced the "N dishes still need someone" nudge.
    @Test
    fun pricedZeroQuantityLine_isNotResolved() {
        val items = listOf(BillItem("ghost", 2500L, quantity = 0), BillItem("beer", 1000L, quantity = 1))
        val r = splitBill(items, indiv(IndividualClaim("beer", A, 1)), shared(), BillExtras())
        assertEquals(ItemStatus.UNCLAIMED, statusOf(r, "ghost"))
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "beer"))
        assertEquals(1, r.unclaimedCount)
        assertTrue(!r.fullyResolved)
    }

    // …and the zero-PRICED zero-quantity line stays RESOLVED: that one is the scanner's empty row, it
    // owes nothing, and nagging about it would be a dead end (nobody can claim a line with no units).
    @Test
    fun freeZeroQuantityLine_staysResolved() {
        val items = listOf(BillItem("ghost", 0L, quantity = 0), BillItem("beer", 1000L, quantity = 1))
        val r = splitBill(items, indiv(IndividualClaim("beer", A, 1)), shared(), BillExtras())
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "ghost"))
        assertTrue(r.fullyResolved)
    }

    // --- determinism (D3) -------------------------------------------------------------------------

    // Shared portions consume the line's penny-exact unit costs in list order, so on an indivisible
    // line whoever comes first pays the odd cent. The list is whatever `ItemShareDao` returned (no
    // `ORDER BY`), which differs between the device that authored the portions and one that pulled
    // them — two devices derived different money for the same bill. Sorting on `portion_id` fixes it.
    @Test
    fun portions_orderIndependent_oddCent() {
        val items = listOf(BillItem("dosa", 1000L, quantity = 3)) // 334 / 333 / 333
        val portions =
            listOf(
                SharedPortion("dosa", "p1", quantity = 1, members = listOf(A)),
                SharedPortion("dosa", "p2", quantity = 1, members = listOf(B)),
                SharedPortion("dosa", "p3", quantity = 1, members = listOf(C)),
            )
        val expected = mapOf(A to 334L, B to 333L, C to 333L)
        for (order in permutations(portions)) {
            val r = splitBill(items, indiv(), shared(), BillExtras(), sharedPortions = order)
            assertEquals(expected, r.owedByUser, "portion order ${order.map { it.portionId }} changed the money")
            assertEquals(expected, r.perItemByUser["dosa"])
        }
    }

    // Multi-member portions too: the odd cent must follow the portion, not the row order it arrived in.
    @Test
    fun portions_orderIndependent_acrossMemberSets() {
        val items = listOf(BillItem("platter", 1000L, quantity = 3))
        val portions =
            listOf(
                SharedPortion("platter", "p1", quantity = 1, members = listOf(A, B)),
                SharedPortion("platter", "p2", quantity = 2, members = listOf(C, D, E)),
            )
        // p1 takes the first unit (334, halved) and p2 the other two (333 + 333, split three ways).
        val expected = mapOf(A to 167L, B to 167L, C to 222L, D to 222L, E to 222L)
        assertEquals(1000L, expected.values.sum())
        for (order in permutations(portions)) {
            assertEquals(
                expected,
                splitBill(items, indiv(), shared(), BillExtras(), sharedPortions = order).owedByUser,
            )
        }
    }

    /** Every ordering of [xs] — the point of the D3 tests is that none of them may change the money. */
    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) {
            listOf(xs)
        } else {
            xs.indices.flatMap { i ->
                permutations(xs.filterIndexed { j, _ -> j != i }).map { listOf(xs[i]) + it }
            }
        }
}
