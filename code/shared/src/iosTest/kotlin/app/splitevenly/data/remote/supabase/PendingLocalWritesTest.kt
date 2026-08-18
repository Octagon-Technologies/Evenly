package app.splitevenly.data.remote.supabase

import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.ExpenseSyncStateEntity
import app.splitevenly.data.db.entity.RowSyncStateEntity
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.db.entity.rowFingerprint
import app.splitevenly.data.db.inMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S2, route (a) — `push()` returning `Ok` is not "every local row reached the server".
 *
 * `pushExpenses` applies #8's in-flight-edit guard: if the local expense's `row_version` moved between
 * the pre-RPC snapshot and adoption, the sync-state stamp is skipped and the expense stays dirty *on
 * purpose*, so the next push carries the newer edit. Nothing throws on that path, so `firstError` stays
 * null and `push()` reports `Ok` — and sign-out used to read that `Ok` as "the cache is a pure mirror"
 * and wipe. The count is the only thing that sees the difference, which is why sign-out now takes it on
 * every attempt rather than only after a failed push.
 *
 * Against a real Room DB rather than a fake, because the thing being pinned is the comparison
 * `countPendingLocalWrites` makes against `expense_sync_state` and `row_sync_state` — a fake of those
 * would just be the assertion written twice. Kotlin/Native for the same reason `SignOutWipeTest` is.
 */
class PendingLocalWritesTest {
    private lateinit var db: EvenlyDatabase
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        // No network is reachable in a unit test and none is needed: every call below reads Room only.
        engine = SyncEngine(createEvenlySupabaseClient(), db)
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun expense(rowVersion: Long) =
        ExpenseEntity(
            id = "e1",
            groupId = "g1",
            title = "Dinner",
            amountSubunits = 4800,
            currency = "USD",
            payerUserId = "a",
            expenseDate = "2026-08-15",
            splitMode = "EVEN",
            createdBy = "a",
            rowVersion = rowVersion,
            createdAt = 1,
            updatedAt = 1,
        )

    @Test
    fun anExpenseLeftDirtyByTheInFlightEditGuard_isCounted() =
        runTest {
            // Exactly the state the guard leaves: the server confirmed version 1, a local edit took the
            // row to version 2, and no sync-state stamp followed because adoption was skipped.
            db.expenseDao().upsertAll(listOf(expense(rowVersion = 2)))
            db.expenseSyncStateDao().upsert(ExpenseSyncStateEntity("e1", syncedVersion = 1, syncedSplitVersion = 1))

            val pending = engine.countPendingLocalWrites()
            assertEquals(1, pending, "a dirty expense is a local-only write and must be counted")
        }

    @Test
    fun aBrandNewExpenseThatHasNeverSynced_isCounted() =
        runTest {
            // No sync-state row at all — the create never reached the server.
            db.expenseDao().upsertAll(listOf(expense(rowVersion = 1)))

            assertTrue(engine.countPendingLocalWrites() > 0)
        }

    @Test
    fun aConfirmedExpense_isNotCounted() =
        runTest {
            db.expenseDao().upsertAll(listOf(expense(rowVersion = 3)))
            db.expenseSyncStateDao().upsert(ExpenseSyncStateEntity("e1", syncedVersion = 3, syncedSplitVersion = 1))

            assertEquals(0, engine.countPendingLocalWrites(), "a clean cache must not block sign-out")
        }

    @Test
    fun aRowWhoseContentChangedSinceItsFingerprint_isCounted() =
        runTest {
            // The same comparison for the fingerprint-tracked tables: an edited profile that has not been
            // pushed is a local-only write too, and wiping it loses the user's own display name.
            val user = UserEntity(id = "a", displayName = "Ama renamed this", createdAt = 1, updatedAt = 2)
            db.userDao().upsert(user)
            db.rowSyncStateDao().upsertAll(listOf(RowSyncStateEntity("users", "a", syncedHash = rowFingerprint(user) + 1)))

            assertEquals(1, engine.countPendingLocalWrites())
        }

    @Test
    fun anEmptyCacheIsClean() =
        runTest {
            assertEquals(0, engine.countPendingLocalWrites())
        }
}
