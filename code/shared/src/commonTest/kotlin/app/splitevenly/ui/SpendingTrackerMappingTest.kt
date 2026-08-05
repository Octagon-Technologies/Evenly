package app.splitevenly.ui

import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.domain.expense.CategoryDefaults
import app.splitevenly.domain.expense.Expense
import app.splitevenly.domain.expense.ExpenseShare
import app.splitevenly.domain.expense.ExpenseWithShares
import app.splitevenly.domain.expense.GroupCategory
import app.splitevenly.ui.screen.group.buildCategorySpend
import app.splitevenly.ui.screen.group.buildGroupHistory
import app.splitevenly.ui.screen.group.buildPersonalCategorySpend
import app.splitevenly.ui.screen.group.buildPersonalHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure mapping checks for the Personal/Group spending tracker (Overview tab). */
class SpendingTrackerMappingTest {

    private val me = UserId("me")
    private val other = UserId("other")

    @Test
    fun personalSpend_attributes_only_your_owed_share_by_category() {
        // $100 food split 60/40 (you 60); $50 transport you owe all of it.
        val data = listOf(
            withShares(expense("e1", "Dinner", 10000, "food"), me to 6000L, other to 4000L),
            withShares(expense("e2", "Taxi", 5000, "transport"), me to 5000L),
        )
        val spend = buildPersonalCategorySpend(data, me)

        assertEquals(2, spend.size)
        // 6000 food + 5000 transport = 11000 personal total
        assertEquals(50.0, spend.first { it.label == "Transport" }.amount)
        assertEquals(60.0, spend.first { it.label == "Food & Drink" }.amount)
        assertEquals("Food & Drink", spend.first().label, "largest slice (6000 food) sorts first")
    }

    @Test
    fun payer_with_no_share_spends_nothing_but_group_total_is_full() {
        // You paid the full $100 villa but are NOT a participant (other owes all of it).
        val villa = withShares(expense("e1", "Villa", 10000, "lodging", payer = me), other to 10000L)

        // Personal: nothing attributed to you — no slice, no history card.
        assertTrue(buildPersonalCategorySpend(listOf(villa), me).isEmpty(), "paying but not participating is not personal spend")
        assertTrue(buildPersonalHistory(listOf(villa), me).isEmpty(), "no personal history card for a 0-share expense")

        // Group: the full $100 still counts.
        val group = buildCategorySpend(listOf(villa.expense))
        assertEquals(1, group.size)
        assertEquals(100.0, group.first().amount)
        assertEquals(10000L, buildGroupHistory(listOf(villa.expense)).single().amountSubunits)
    }

    @Test
    fun personalHistory_uses_owed_even_when_settled_and_keeps_order() {
        // A fully-settled share (remaining 0) still counts as what you spent on the trip.
        val data = listOf(
            withShares(expense("e1", "Hotel", 8000, "lodging"), me to 4000L, owedToYou = 4000L, remaining = 0L),
            withShares(expense("e2", "Lunch", 3000, "food"), me to 1500L),
        )
        val history = buildPersonalHistory(data, me)

        assertEquals(listOf("Hotel", "Lunch"), history.map { it.title }, "input order preserved")
        assertEquals(4000L, history.first().amountSubunits, "settled share still counts (owed, not remaining)")
    }

    @Test
    fun groupSpend_gives_a_custom_category_its_own_slice_and_falls_back_for_unknown_keys() {
        // A group that customized: built-in defaults + one custom category (uuid key, not an enum id).
        val categories = CategoryDefaults.all + GroupCategory(
            key = "cat-uuid", label = "Scuba", iconToken = "ticket", colorHex = 0xFF2563EB, isDefault = false, sortOrder = 99,
        )
        val spend = buildCategorySpend(
            listOf(
                expense("e1", "Dive trip", 8000, "cat-uuid"),
                expense("e2", "Dinner", 2000, "food"),
            ),
            categories,
        )
        assertEquals(2, spend.size)
        assertEquals(80.0, spend.first { it.label == "Scuba" }.amount, "custom category is its own slice, not bucketed into Other")
        assertEquals("Scuba", spend.first().label, "largest slice (8000) sorts first")

        // A dangling key (e.g. a since-deleted category) falls back to Other rather than vanishing/crashing.
        val unknown = buildCategorySpend(listOf(expense("e3", "Mystery", 1000, "deleted-key")), categories)
        assertEquals("Other", unknown.single().label)
    }

    private fun expense(id: String, title: String, amount: Long, categoryId: String, payer: UserId = me) = Expense(
        id = ExpenseId(id),
        groupId = GroupId("g"),
        title = title,
        amountSubunits = amount,
        currency = "USD",
        expenseDate = "2026-06-15",
        payerUserId = payer,
        payerOutsideName = null,
        splitMode = "EXACT",
        status = "ACTIVE",
        notes = null,
        categoryId = categoryId,
        createdBy = payer,
        createdAt = 0,
        rowVersion = 1,
    )

    /** Build an expense + shares. Each (user, owed) becomes a fully-unpaid share unless overridden. */
    private fun withShares(
        expense: Expense,
        vararg shares: Pair<UserId, Long>,
        owedToYou: Long? = null,
        remaining: Long? = null,
    ) = ExpenseWithShares(
        expense,
        shares.mapIndexed { i, (user, owed) ->
            ExpenseShare(
                id = "${expense.id.value}-$i",
                userId = user,
                owedSubunits = if (user == me && owedToYou != null) owedToYou else owed,
                remainingSubunits = if (user == me && remaining != null) remaining else owed,
            )
        },
    )
}
