package app.splitevenly.data.db.dao

import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.ItemShareEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Room tests for [ItemShareDao] under the portion key. Uniqueness is `(item_id, user_id, portion_id)`
 * over active rows — one person CAN hold several portions of the same line at once (solo on one
 * serving, shared with someone else on another), so any "this user's membership on this item" read
 * must return them all, never an arbitrary one.
 */
class ItemShareDaoTest {
    private lateinit var db: EvenlyDatabase
    private lateinit var dao: ItemShareDao

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        dao = db.itemShareDao()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun share(
        id: String,
        portionId: String?,
        userId: String = "u1",
        deletedAt: Long? = null,
    ) = ItemShareEntity(
        id = id,
        itemId = "i1",
        expenseId = "e1",
        groupId = "g1",
        userId = userId,
        portionId = portionId,
        quantity = 1,
        addedBy = "u1",
        createdAt = 100L,
        updatedAt = 100L,
        deletedAt = deletedAt,
    )

    @Test
    fun getActiveShares_returnsEveryPortionMembership_excludingTombstones() =
        runTest {
            dao.upsertAll(
                listOf(
                    share("s1", portionId = "p1"),
                    share("s2", portionId = "p2"),
                    share("s3", portionId = "p3", deletedAt = 50L),
                    share("s4", portionId = "p1", userId = "someone_else"),
                ),
            )

            val mine = dao.getActiveShares("i1", "u1").map { it.id }.sorted()
            assertEquals(listOf("s1", "s2"), mine)
        }

    @Test
    fun getActiveShares_noMemberships_returnsEmpty() =
        runTest {
            assertEquals(emptyList(), dao.getActiveShares("i1", "u1"))
        }
}
