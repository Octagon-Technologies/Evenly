package app.splitevenly.data.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.ExpenseId
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.expense.BillExtrasInput
import app.splitevenly.domain.expense.EditBill
import app.splitevenly.domain.expense.EditBillItem
import app.splitevenly.domain.expense.NewBill
import app.splitevenly.domain.expense.NewBillItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bill editor auto-saves while typing; one sitting must not log an "edited" per pause. */
class BillHistoryCoalesceTest {
    private lateinit var db: EvenlyDatabase
    private lateinit var bills: BillRepositoryImpl

    private val group = GroupId("g1")
    private val me = UserId("a")
    private val bob = UserId("b")

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        bills =
            BillRepositoryImpl(
                db.expenseDao(),
                db.expenseItemDao(),
                db.itemClaimDao(),
                db.itemShareDao(),
                db.billParticipantDao(),
                db.shareDao(),
                db.billWriteDao(),
                clockAt("2026-06-28"),
                historyEventDao = db.historyEventDao(),
            )
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun edit(
        id: ExpenseId,
        by: UserId,
        price: Long,
    ) {
        val item = db.expenseItemDao().getByExpense(id.value).first()
        bills.editBill(
            id,
            EditBill(
                title = "Dinner",
                expenseDate = "2026-06-28",
                payerUserId = me,
                items = listOf(EditBillItem(item.id, item.label, 1, price)),
                extras = BillExtrasInput(),
                editedBy = by,
            ),
        )
    }

    @Test
    fun editsBySamePersonCoalesceAndAnotherPersonStillLogs() =
        runTest {
            val id =
                (
                    bills.createBill(
                        NewBill(
                            groupId = group,
                            title = "Dinner",
                            currency = "USD",
                            expenseDate = "2026-06-28",
                            payerUserId = me,
                            createdBy = me,
                            items = listOf(NewBillItem("Pasta", 1, 1800)),
                            extras = BillExtrasInput(),
                        ),
                    ) as AppResult.Ok
                ).value

            edit(id, me, 1900)
            edit(id, me, 2000)
            edit(id, bob, 2100)
            edit(id, bob, 2200)

            val events =
                db
                    .historyEventDao()
                    .observeByExpense(id.value)
                    .first()
                    .sortedBy { it.type }
                    .map { it.type to it.actorUserId }
            assertEquals(listOf("CREATED" to me.value, "EDITED" to bob.value), events)
        }
}
