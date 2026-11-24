package app.splitevenly.domain.people

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeopleTest {
    private val me = "me"

    private fun line(
        group: String,
        debtor: String,
        creditor: String,
        amount: Long,
        date: Int = 1,
        currency: String = "USD",
        expense: String = "$group-$debtor-$creditor-$date",
    ) = PeopleLine(group, group.replaceFirstChar { it.uppercase() }, "💸", false, expense, expense, "2026-01-" + date.toString().padStart(2, '0'), currency, debtor, creditor, amount)

    private fun member(group: String, id: String, name: String, placeholder: Boolean = true) =
        RosterEntry(group, group.replaceFirstChar { it.uppercase() }, "💸", id, name, placeholder)

    private fun roster(vararg entries: RosterEntry) = entries.toList() + entries.map { it.groupId }.distinct().map { member(it, me, "Me", false) }

    @Test
    fun amountInAGroupIsThatGroupsNetAndOweThemGroupsAreLeftOut() {
        val lines =
            listOf(
                line("boston", "p", me, 7000),
                line("boston", me, "p", 1000),
                line("nyc", "p", me, 4500),
                line("nyc", me, "p", 9000),
            )
        val people = buildPeople(me, lines, roster(member("boston", "p", "Purity", false), member("nyc", "p", "Purity", false)), emptyList())
        val purity = people.owing.single()
        assertEquals(listOf("boston" to 6000L), purity.groups.map { it.groupId to it.amountSubunits })
        assertEquals(mapOf("USD" to 6000L), purity.totals)
        assertEquals(1000L, purity.offsets.single().amountSubunits)
    }

    @Test
    fun linkedNamesAddUpAndShowTheirAlias() {
        val lines = listOf(line("nyc", "pur", me, 4500, date = 5), line("boston", "pookie", me, 7000, date = 9))
        val links = listOf(NameLink("l1", "pookie", "pur", LinkKind.SAME))
        val people = buildPeople(me, lines, roster(member("nyc", "pur", "Purity"), member("boston", "pookie", "Pookie")), links)
        val purity = people.owing.single()
        assertEquals("Purity", purity.name)
        assertEquals(mapOf("USD" to 11500L), purity.totals)
        assertEquals("Pookie", purity.groups.single { it.groupId == "boston" }.alias)
        assertEquals(listOf("boston", "nyc"), purity.expenses.map { it.groupId })
    }

    @Test
    fun aLinkBetweenTwoNamesInOneGroupIsIgnored() {
        val lines = listOf(line("nyc", "a", me, 100), line("nyc", "b", me, 200))
        val links = listOf(NameLink("l1", "b", "a", LinkKind.SAME))
        val people = buildPeople(me, lines, roster(member("nyc", "a", "Ann"), member("nyc", "b", "Bea")), links)
        assertEquals(2, people.owing.size)
    }

    @Test
    fun twoAccountsAreNeverMerged() {
        val lines = listOf(line("nyc", "a", me, 100), line("boston", "b", me, 200))
        val links = listOf(NameLink("l1", "b", "a", LinkKind.SAME))
        val people = buildPeople(me, lines, roster(member("nyc", "a", "Ann", false), member("boston", "b", "Ann", false)), links)
        assertEquals(2, people.owing.size)
    }

    @Test
    fun sameNameIsSuggestedUnlessRuledOutOrInTheSameGroup() {
        val lines = listOf(line("nyc", "p1", me, 4500), line("ski", "p2", me, 1200), line("apt", "p3", me, 900))
        val r = roster(member("nyc", "p1", "Purity"), member("ski", "p2", " purity "), member("apt", "p3", "Poonam"))
        val purity = buildPeople(me, lines, r, emptyList()).owing.single { it.key == "p1" }
        assertEquals(listOf("p2"), purity.sameNameMatches.map { it.userId })

        val ruledOut = buildPeople(me, lines, r, listOf(NameLink("n1", "p2", "p1", LinkKind.NOT_SAME)))
        assertTrue(ruledOut.owing.single { it.key == "p1" }.sameNameMatches.isEmpty())

        val sameGroup = roster(member("nyc", "p1", "Purity"), member("nyc", "p2", "Purity"))
        val two = buildPeople(me, listOf(line("nyc", "p1", me, 1), line("nyc", "p2", me, 1)), sameGroup, emptyList())
        assertTrue(two.owing.all { it.sameNameMatches.isEmpty() })
    }

    @Test
    fun someoneFullyPaidBackIsSettled() {
        val lines = listOf(line("nyc", "s", me, 0))
        val people = buildPeople(me, lines, roster(member("nyc", "s", "Sam")), emptyList())
        assertTrue(people.owing.isEmpty())
        assertEquals("Sam", people.settled.single().name)
    }

    @Test
    fun poolLeavesOutNamesInTheirGroupsAndLinkedNames() {
        val lines =
            listOf(
                line("nyc", "pur", me, 4500),
                line("boston", "pookie", me, 7000),
                line("nyc", "maya", me, 800),
                line("ski", "gf", me, 1200),
            )
        val r = roster(member("nyc", "pur", "Purity"), member("boston", "pookie", "Pookie"), member("nyc", "maya", "Maya"), member("ski", "gf", "GF"))
        val people = buildPeople(me, lines, r, emptyList())
        val purity = people.owing.single { it.key == "pur" }
        assertEquals(setOf("pookie", "gf"), linkPool(purity, people).map { it.userId }.toSet())

        val linked = buildPeople(me, lines, r, listOf(NameLink("l", "pookie", "pur", LinkKind.SAME)))
        val purity2 = linked.owing.single { it.key == "pur" }
        assertEquals(setOf("gf"), linkPool(purity2, linked).map { it.userId }.toSet())
    }

    @Test
    fun currenciesAreNeverSummed() {
        val lines = listOf(line("nyc", "p", me, 100, currency = "USD"), line("nbo", "p", me, 5000, currency = "KES"))
        val people = buildPeople(me, lines, roster(member("nyc", "p", "P", false), member("nbo", "p", "P", false)), emptyList())
        assertEquals(mapOf("USD" to 100L, "KES" to 5000L), people.owing.single().totals)
    }

    @Test
    fun oldestFirstClearsOldGroupsThenStops() {
        val targets = listOf(SplitTarget("boston", 7000, "2026-09-06"), SplitTarget("nj", 1500, "2026-03-02"), SplitTarget("nyc", 4500, "2026-06-18"))
        assertEquals(mapOf("nj" to 1500L, "nyc" to 4500L, "boston" to 4000L), oldestFirst(10000, targets))
    }

    @Test
    fun aSplitOnlyAddsUpWhenTheTotalMatchesAndNoGroupIsOverItsBalance() {
        val targets = listOf(SplitTarget("nj", 1500, "2026-03-02"), SplitTarget("nyc", 4500, "2026-06-18"), SplitTarget("boston", 7000, "2026-09-06"))
        val short = checkSplit(10000, mapOf("nj" to 1500, "nyc" to 3000, "boston" to 4000), targets)
        assertEquals(1500L, short.leftSubunits)
        assertFalse(short.addsUp)

        val overGroup = checkSplit(10000, mapOf("nj" to 0, "nyc" to 2000, "boston" to 8000), targets)
        assertEquals(setOf("boston"), overGroup.overBalance)
        assertFalse(overGroup.addsUp)

        assertTrue(checkSplit(10000, mapOf("nj" to 1500, "nyc" to 4500, "boston" to 4000), targets).addsUp)
    }
}
