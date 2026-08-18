package app.splitevenly.data.db.dao

import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.CategoryEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Room tests for [CategoryDao]. The interesting case is key reuse: the server's uniqueness on
 * `(group_id, key)` is a partial index over ACTIVE rows only, so a tombstoned custom category can
 * legitimately coexist with a live successor under the same key — and writes keyed on
 * `(group_id, key)` must therefore only ever touch the live row.
 */
class CategoryDaoTest {
    private lateinit var db: EvenlyDatabase
    private lateinit var dao: CategoryDao

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        dao = db.categoryDao()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun category(
        id: String,
        key: String,
        deletedAt: Long? = null,
        updatedAt: Long = 100L,
        rowVersion: Long = 1L,
    ) = CategoryEntity(
        id = id,
        groupId = "g1",
        key = key,
        label = "Label $id",
        icon = "tag",
        color = "#2563EB",
        createdAt = 100L,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        rowVersion = rowVersion,
    )

    @Test
    fun softDelete_leavesExistingTombstoneOfTheSameKeyUntouched() =
        runTest {
            // A custom category deleted months ago, and a live successor reusing its key.
            dao.upsertAll(
                listOf(
                    category("old", key = "coffee", deletedAt = 50L, updatedAt = 50L, rowVersion = 2L),
                    category("new", key = "coffee"),
                ),
            )

            dao.softDelete("g1", "coffee", now = 9_000L)

            val rows = db.categoryDao().allForSync().associateBy { it.id }
            // The live row is the one that dies now…
            assertEquals(9_000L, rows.getValue("new").deletedAt)
            assertEquals(9_000L, rows.getValue("new").updatedAt)
            assertEquals(2L, rows.getValue("new").rowVersion)
            // …and the old tombstone is not re-stamped (a re-stamp re-pushes a row nothing changed).
            assertEquals(50L, rows.getValue("old").deletedAt)
            assertEquals(50L, rows.getValue("old").updatedAt)
            assertEquals(2L, rows.getValue("old").rowVersion)
        }

    @Test
    fun softDelete_tombstonesTheLiveRow() =
        runTest {
            dao.upsert(category("c1", key = "food"))
            dao.softDelete("g1", "food", now = 500L)
            assertEquals(0, dao.countActiveInGroup("g1"))
        }
}
