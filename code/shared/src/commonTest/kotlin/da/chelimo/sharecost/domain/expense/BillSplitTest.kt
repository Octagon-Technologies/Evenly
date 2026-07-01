package da.chelimo.sharecost.domain.expense

import da.chelimo.sharecost.core.id.UserId
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
    private fun statusOf(r: BillResult, itemId: String) = r.items.first { it.itemId == itemId }.status

    // The engine's truth is the LINE TOTAL, but these scenarios read more clearly as a per-unit price;
    // this helper builds the line total (unit × quantity) so the expected owed values stay obvious.
    private fun item(itemId: String, unitPriceSubunits: Long, quantity: Int) =
        BillItem(itemId, unitPriceSubunits * quantity, quantity)

    // Individual whole units: each claimed unit costs exactly its price; fully claimed → RESOLVED.
    @Test
    fun individualUnits_exact_andResolved() {
        val items = listOf(item("pizza", 1800L, quantity = 4))
        val r = splitBill(
            items,
            indiv(IndividualClaim("pizza", A, 2), IndividualClaim("pizza", B, 1), IndividualClaim("pizza", C, 1)),
            shared(), BillExtras(),
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
        val r = splitBill(
            items,
            indiv(IndividualClaim("pizza", A, 2)),
            shared(SharedMember("pizza", B), SharedMember("pizza", C)), // 2 leftover units ÷ 2
            BillExtras(),
        )
        assertEquals(mapOf(A to 2000L, B to 1000L, C to 1000L), r.owedByUser)
        assertEquals(4000L, r.owedByUser.values.sum()) // whole line covered
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "pizza"))
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
        val r = splitBill(
            items,
            indiv(IndividualClaim("makhani", A, 1), IndividualClaim("makhani", B, 1), IndividualClaim("makhani", C, 1)),
            shared(*listOf(A, B, C, D, E, F).map { SharedMember("makhani", it) }.toTypedArray()), // 4th ÷ 6
            BillExtras(),
        )
        assertEquals(2800L, r.owedByUser[A]) // $24 own + $4 share of the 4th
        assertEquals(400L, r.owedByUser[D])  // just $4 share
        assertEquals(9600L, r.owedByUser.values.sum()) // whole $96 line
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "makhani"))
    }

    // Indivisible shared split stays penny-exact via largest-remainder.
    @Test
    fun sharedSplit_indivisible_isPennyExact() {
        val items = listOf(item("nachos", 1000L, quantity = 1))
        val r = splitBill(items, indiv(), shared(SharedMember("nachos", A), SharedMember("nachos", B), SharedMember("nachos", C)), BillExtras())
        assertEquals(1000L, r.owedByUser.values.sum())
        assertEquals(listOf(334L, 333L, 333L), r.owedByUser.values.sortedDescending())
    }

    // Line total is the truth: a $10.00 line over 3 individually-claimed units splits penny-exact, no
    // leak, even though 1000 ÷ 3 isn't whole (largest-remainder gives 334/333/333, summing to $10.00).
    @Test
    fun lineTotalIndivisibleByUnits_isPennyExact() {
        val items = listOf(BillItem("dosa", 1000L, quantity = 3)) // $10.00 total for 3 (NOT per-unit)
        val r = splitBill(
            items,
            indiv(IndividualClaim("dosa", A, 1), IndividualClaim("dosa", B, 1), IndividualClaim("dosa", C, 1)),
            shared(), BillExtras(),
        )
        assertEquals(1000L, r.owedByUser.values.sum()) // exactly $10 — nothing lost to rounding
        assertEquals(listOf(334L, 333L, 333L), r.owedByUser.values.sortedDescending())
        assertEquals(ItemStatus.RESOLVED, statusOf(r, "dosa"))
    }

    // Over-claim (more individual units than ordered) → OVERCLAIMED flag; still costs every claimed unit.
    @Test
    fun overClaim_flagged() {
        val items = listOf(item("beer", 900L, quantity = 2))
        val r = splitBill(items, indiv(IndividualClaim("beer", A, 1), IndividualClaim("beer", B, 1), IndividualClaim("beer", C, 1)), shared(), BillExtras())
        assertEquals(2700L, r.owedByUser.values.sum())
        assertEquals(ItemStatus.OVERCLAIMED, statusOf(r, "beer"))
        assertEquals(1, r.overClaimedCount)
    }

    // Tax + gratuity proportional, tip even (the default).
    @Test
    fun taxGratuityProportional_tipEven() {
        val items = listOf(item("a-meal", 6000L, 1), item("b-meal", 4000L, 1))
        val r = splitBill(
            items,
            indiv(IndividualClaim("a-meal", A, 1), IndividualClaim("b-meal", B, 1)),
            shared(),
            BillExtras(taxSubunits = 800L, gratuitySubunits = 1200L, tipSubunits = 1000L, tipSplitMode = TipSplitMode.EVEN),
        )
        assertEquals(6000L + 1200L + 500L, r.owedByUser[A]) // 7700
        assertEquals(4000L + 800L + 500L, r.owedByUser[B])  // 5300
        assertEquals(13000L, r.owedByUser.values.sum())
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

    // Nothing claimed → empty tab, every line UNCLAIMED, never a divide-by-zero on a proportional extra.
    @Test
    fun nothingClaimed_empty_allUnclaimed() {
        val items = listOf(item("x", 1000L, 1), item("y", 500L, 2))
        val r = splitBill(items, indiv(), shared(), BillExtras(taxSubunits = 100L))
        assertTrue(r.owedByUser.isEmpty())
        assertEquals(2, r.unclaimedCount)
        assertTrue(!r.fullyResolved)
    }
}
