package app.splitevenly.data.db

import app.splitevenly.data.db.entity.BillParticipantEntity
import app.splitevenly.data.db.entity.CategoryEntity
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.ExpenseItemEntity
import app.splitevenly.data.db.entity.ExpenseSyncStateEntity
import app.splitevenly.data.db.entity.GroupEntity
import app.splitevenly.data.db.entity.GroupPassEntity
import app.splitevenly.data.db.entity.ItemClaimEntity
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.RowSyncStateEntity
import app.splitevenly.data.db.entity.SettlementEntity
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.entity.UserEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #24 — signing out used to leave Room fully populated, so the next account on the device pushed the
 * previous one's rows up under its own session. This pins the wipe that closes it.
 *
 * **Deliberately an iOS (Kotlin/Native) test.** The obvious implementation of this wipe is
 * `RoomDatabase.clearAllTables()`, which resolves on Android/JVM and fails on Native (`AGENTS.md`
 * §4.1) — so an Android-only test would have passed against exactly the code that cannot ship.
 *
 * The assertion is the one that matters for the leak: after a sign-out wipe, `allForSync()` — the
 * method `SyncEngine.push` consumes — has nothing of account A's left to send.
 */
class SignOutWipeTest {
    private lateinit var db: EvenlyDatabase

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun seedAccountA() {
        db.userDao().upsert(UserEntity(id = "a", displayName = "Ama", createdAt = 1, updatedAt = 1))
        db.groupDao().upsertAll(
            listOf(GroupEntity(id = "g1", name = "Ski trip", inviteToken = "tok", createdBy = "a", createdAt = 1, updatedAt = 1)),
        )
        db.memberDao().upsertAll(
            listOf(
                MemberEntity(
                    id = "m1",
                    groupId = "g1",
                    userId = "a",
                    status = MemberEntity.STATUS_ACTIVE,
                    joinedAt = 1,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )
        db.expenseDao().upsertAll(
            listOf(
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
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )
        db.shareDao().upsertAll(
            listOf(
                ShareEntity(
                    id = "e1__a",
                    expenseId = "e1",
                    userId = "a",
                    shareOwedSubunits = 4800,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )
        db.settlementDao().upsertAll(
            listOf(
                SettlementEntity(
                    id = "s1",
                    groupId = "g1",
                    fromUserId = "a",
                    toUserId = "b",
                    paymentCurrency = "USD",
                    paymentAmountSubunits = 100,
                    settledAt = 1,
                    createdBy = "a",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )
        db.categoryDao().upsertAll(
            listOf(
                CategoryEntity(
                    id = "g1__food",
                    groupId = "g1",
                    key = "food",
                    label = "Food",
                    icon = "food",
                    color = "#000000",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )
        db.expenseItemDao().upsertAll(
            listOf(
                ExpenseItemEntity(
                    id = "i1",
                    expenseId = "e1",
                    groupId = "g1",
                    label = "Pizza",
                    quantity = 1,
                    unitPriceSubunits = 4800,
                    lineTotalSubunits = 4800,
                    sortOrder = 0,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )
        db.itemClaimDao().upsertAll(
            listOf(
                ItemClaimEntity(
                    id = "i1__a",
                    itemId = "i1",
                    expenseId = "e1",
                    groupId = "g1",
                    userId = "a",
                    quantity = 1,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )
        db.billParticipantDao().upsertAll(
            listOf(
                BillParticipantEntity(
                    id = "e1__a",
                    expenseId = "e1",
                    groupId = "g1",
                    userId = "a",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )
        // The Pro entitlement mirror: per-USER, so inheriting it hands the next account paid scans.
        db.groupPassDao().upsertAll(
            listOf(
                GroupPassEntity(
                    id = "p1",
                    groupId = "g1",
                    purchasedBy = "a",
                    tier = "week",
                    store = "app_store",
                    storeTxnId = "t1",
                    rcAppUserId = "a",
                    startsAt = 1,
                    expiresAt = 9_999_999,
                    createdAt = 1,
                    updatedAt = 1,
                ),
            ),
        )
        // Sync bookkeeping. If these outlive the rows they describe, the NEXT account's freshly-pulled
        // rows look already-pushed and never sync at all.
        db.rowSyncStateDao().upsertAll(listOf(RowSyncStateEntity("users", "a", 42L)))
        db.expenseSyncStateDao().upsert(ExpenseSyncStateEntity("e1", 1, 1))
    }

    @Test
    fun wipe_leavesNothingOfTheSignedOutAccountForThePushToSend() =
        runTest {
            seedAccountA()
            assertTrue(db.userDao().allForSync().isNotEmpty(), "sanity: the account was actually seeded")

            db.signOutWipeDao().wipeSignedOutAccount()

            // Every source `SyncEngine.push` reads from. A table missing from the wipe shows up here as
            // account A's row still queued to go up under whoever signs in next.
            assertTrue(db.userDao().allForSync().isEmpty(), "users")
            assertTrue(db.groupDao().allForSync().isEmpty(), "groups")
            assertTrue(db.memberDao().allForSync().isEmpty(), "members")
            assertTrue(db.expenseDao().allForSync().isEmpty(), "expenses")
            assertTrue(db.settlementDao().allForSync().isEmpty(), "settlements")
            assertTrue(db.categoryDao().allForSync().isEmpty(), "categories")
            assertTrue(db.expenseItemDao().allForSync().isEmpty(), "expense_items")
            assertTrue(db.itemClaimDao().allForSync().isEmpty(), "item_claims")
            assertTrue(db.billParticipantDao().allForSync().isEmpty(), "bill_participants")
            assertTrue(db.shareDao().getByExpense("e1").isEmpty(), "shares")
        }

    @Test
    fun wipe_dropsTheProEntitlementMirror() =
        runTest {
            seedAccountA()

            db.signOutWipeDao().wipeSignedOutAccount()

            // Pull-only and per-user: a surviving pass is free unlimited paid Claude-vision calls for the
            // next person to sign in on this phone.
            assertTrue(db.groupPassDao().forGroup("g1").isEmpty(), "group_passes")
        }

    @Test
    fun wipe_dropsSyncBookkeepingWithTheRowsItDescribes() =
        runTest {
            seedAccountA()

            db.signOutWipeDao().wipeSignedOutAccount()

            assertTrue(db.rowSyncStateDao().forTable("users").isEmpty(), "row_sync_state")
            assertTrue(db.expenseSyncStateDao().all().isEmpty(), "expense_sync_state")
        }

    @Test
    fun wipe_isIdempotent() =
        runTest {
            seedAccountA()

            db.signOutWipeDao().wipeSignedOutAccount()
            db.signOutWipeDao().wipeSignedOutAccount()

            assertEquals(0, db.userDao().allForSync().size)
        }
}
