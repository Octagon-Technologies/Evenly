package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.ExpenseId
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId
import da.chelimo.sharecost.data.db.ShareCostDatabase
import da.chelimo.sharecost.data.db.entity.ExpenseEditConflictEntity
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.inMemoryTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pick-a-side resolution of a parked edit-collision (Phase E). Verifies the repository re-applies the
 * rejected payload by USER identity (so allocations survive) and closes the conflict — or keeps the
 * canonical version — never auto-merging two money splits.
 */
@OptIn(ExperimentalSerializationApi::class)
class EditConflictResolutionTest {

    private lateinit var db: ShareCostDatabase
    private lateinit var repo: ExpenseRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; namingStrategy = JsonNamingStrategy.SnakeCase }

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        repo = ExpenseRepositoryImpl(
            db.expenseDao(), db.shareDao(), clockAt("2026-06-12"),
            editConflictDao = db.expenseEditConflictDao(),
        )
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun seedCanonical() {
        db.expenseDao().upsert(
            ExpenseEntity(
                id = "e1", groupId = "g1", title = "Dinner", amountSubunits = 3000, currency = "USD",
                expenseDate = "2026-06-01", payerUserId = "u1", splitMode = "EVEN",
                createdBy = "u1", createdAt = 1, updatedAt = 1, rowVersion = 5,
            ),
        )
        db.shareDao().upsertAll(
            listOf(
                ShareEntity(id = "s1", expenseId = "e1", userId = "u1", shareOwedSubunits = 1000, createdAt = 1, updatedAt = 1),
                ShareEntity(id = "s2", expenseId = "e1", userId = "u2", shareOwedSubunits = 1000, createdAt = 1, updatedAt = 1),
                ShareEntity(id = "s3", expenseId = "e1", userId = "u3", shareOwedSubunits = 1000, createdAt = 1, updatedAt = 1),
            ),
        )
    }

    private suspend fun park(rejectedTitle: String, rejectedShares: List<ShareEntity>) {
        val rejectedExpense = ExpenseEntity(
            id = "e1", groupId = "g1", title = rejectedTitle, amountSubunits = 3000, currency = "USD",
            expenseDate = "2026-06-01", payerUserId = "u1", splitMode = "EXACT",
            createdBy = "u1", createdAt = 1, updatedAt = 9, rowVersion = 5,
        )
        db.expenseEditConflictDao().upsert(
            ExpenseEditConflictEntity(
                id = "c1", groupId = "g1", expenseId = "e1", baseVersion = 4, serverVersion = 5, rejectedBy = "u2",
                rejectedExpense = json.encodeToString(rejectedExpense),
                rejectedShares = json.encodeToString(rejectedShares),
                createdAt = 10,
            ),
        )
    }

    @Test
    fun useRejected_reappliesPayloadByUserIdentity_andClosesConflict() = runTest {
        seedCanonical()
        // The rejected edit re-cut the split: Alice 0, Bob 1500, Tom 1500, and renamed it.
        park(
            rejectedTitle = "Dinner at Nobu",
            rejectedShares = listOf(
                ShareEntity(id = "rs1", expenseId = "e1", userId = "u1", shareOwedSubunits = 0, createdAt = 1, updatedAt = 9),
                ShareEntity(id = "rs2", expenseId = "e1", userId = "u2", shareOwedSubunits = 1500, createdAt = 1, updatedAt = 9),
                ShareEntity(id = "rs3", expenseId = "e1", userId = "u3", shareOwedSubunits = 1500, createdAt = 1, updatedAt = 9),
            ),
        )

        assertTrue(repo.resolveEditConflict("c1", useRejected = true, resolvedBy = UserId("u1")) is AppResult.Ok)

        val detail = repo.observeExpense(ExpenseId("e1")).first()
        assertNotNull(detail)
        assertEquals("Dinner at Nobu", detail.expense.title)
        val owed = detail.shares.associate { it.userId.value to it.owedSubunits }
        assertEquals(0, owed["u1"])
        assertEquals(1500, owed["u2"])
        assertEquals(1500, owed["u3"])
        // Shares kept their identity (matched by user), so allocations would survive: s1/s2/s3 reused.
        assertEquals(setOf("s1", "s2", "s3"), detail.shares.map { it.id }.toSet())
        // Conflict closed, and the queue is empty.
        assertEquals("USE_REJECTED", db.expenseEditConflictDao().getById("c1")?.resolution)
        assertEquals(emptyList(), repo.observeEditConflicts(GroupId("g1")).first())
    }

    @Test
    fun keepCurrent_leavesCanonicalUntouched_andClosesConflict() = runTest {
        seedCanonical()
        park("Dinner at Nobu", emptyList())

        assertTrue(repo.resolveEditConflict("c1", useRejected = false, resolvedBy = UserId("u1")) is AppResult.Ok)

        val detail = repo.observeExpense(ExpenseId("e1")).first()
        assertNotNull(detail)
        assertEquals("Dinner", detail.expense.title) // unchanged
        assertEquals("KEEP_CURRENT", db.expenseEditConflictDao().getById("c1")?.resolution)
        assertEquals(emptyList(), repo.observeEditConflicts(GroupId("g1")).first())
    }

    @Test
    fun observeEditConflicts_exposesBothSidesAndWinner() = runTest {
        seedCanonical()
        val rejectedExpense = ExpenseEntity(
            id = "e1", groupId = "g1", title = "Dinner at Nobu", amountSubunits = 3000, currency = "USD",
            expenseDate = "2026-06-01", payerUserId = "u1", splitMode = "EXACT",
            createdBy = "u1", createdAt = 1, updatedAt = 9, rowVersion = 5,
        )
        val rejectedShares = listOf(
            ShareEntity(id = "rs1", expenseId = "e1", userId = "u1", shareOwedSubunits = 0, createdAt = 1, updatedAt = 9),
            ShareEntity(id = "rs2", expenseId = "e1", userId = "u2", shareOwedSubunits = 1500, createdAt = 1, updatedAt = 9),
            ShareEntity(id = "rs3", expenseId = "e1", userId = "u3", shareOwedSubunits = 1500, createdAt = 1, updatedAt = 9),
        )
        db.expenseEditConflictDao().upsert(
            ExpenseEditConflictEntity(
                id = "c1", groupId = "g1", expenseId = "e1", baseVersion = 4, serverVersion = 5,
                rejectedBy = "u2", serverActor = "u3",
                rejectedExpense = json.encodeToString(rejectedExpense),
                rejectedShares = json.encodeToString(rejectedShares),
                createdAt = 10,
            ),
        )

        val conflict = repo.observeEditConflicts(GroupId("g1")).first().single()
        assertEquals(UserId("u2"), conflict.rejectedBy)
        assertEquals(UserId("u3"), conflict.winnerBy) // the winner is now recorded, not guessed
        // Current side = the live canonical shares (even split); rejected side = the parked payload.
        assertEquals(1000, conflict.current.shares[UserId("u2")])
        assertEquals(1500, conflict.rejected.shares[UserId("u2")])
        assertEquals("EVEN", conflict.current.splitMode)
        assertEquals("EXACT", conflict.rejected.splitMode)
    }

    @Test
    fun resolve_isIdempotent() = runTest {
        seedCanonical()
        park("Dinner at Nobu", emptyList())
        repo.resolveEditConflict("c1", useRejected = false, resolvedBy = UserId("u1"))
        // Second call is a no-op (already resolved), still Ok.
        assertTrue(repo.resolveEditConflict("c1", useRejected = true, resolvedBy = UserId("u1")) is AppResult.Ok)
        assertEquals("KEEP_CURRENT", db.expenseEditConflictDao().getById("c1")?.resolution)
    }
}
