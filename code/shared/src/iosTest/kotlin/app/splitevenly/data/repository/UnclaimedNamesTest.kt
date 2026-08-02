package app.splitevenly.data.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.db.inMemoryTestDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `observeUnclaimedNames` — the single source behind both the "Is this you?" card and the full-screen
 * list. Everything the feature promises about *which* names get asked about is decided here: the
 * narrowing after each answer, the never-ask-about-your-own-name rule, and the evidence each name
 * carries.
 */
class UnclaimedNamesTest {

    private lateinit var db: EvenlyDatabase
    private lateinit var repo: GroupRepositoryImpl

    private val group = GroupId("g1")
    private val me = UserId("me")

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        repo = GroupRepositoryImpl(
            db.groupDao(), db.memberDao(), db.userDao(), db.expenseDao(), db.shareDao(), db.conflictDao(),
            db.placeholderMergeDao(), db.placeholderClaimAnswerDao(), clockAt("2026-08-01"),
        )
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun member(userId: String, placeholder: Boolean = false, createdBy: String? = null) {
        db.userDao().upsert(
            UserEntity(
                id = userId, isPlaceholder = placeholder, displayName = userId,
                placeholderGroupId = if (placeholder) group.value else null,
                createdBy = createdBy, createdAt = 1, updatedAt = 1,
            ),
        )
        db.memberDao().upsert(
            MemberEntity(id = "m_$userId", groupId = group.value, userId = userId, joinedAt = 1, createdAt = 1, updatedAt = 1),
        )
    }

    private suspend fun booked(userId: String, expenseId: String, owed: Long, currency: String = "USD") {
        db.expenseDao().upsert(
            ExpenseEntity(
                id = expenseId, groupId = group.value, title = expenseId, amountSubunits = owed,
                currency = currency, expenseDate = "2026-07-01", payerUserId = "maya", splitMode = "EVEN",
                createdBy = "maya", createdAt = 1, updatedAt = 1,
            ),
        )
        db.shareDao().upsert(
            ShareEntity(
                id = "s_${expenseId}_$userId", expenseId = expenseId, userId = userId,
                shareOwedSubunits = owed, createdAt = 1, updatedAt = 1,
            ),
        )
    }

    private suspend fun names() = repo.observeUnclaimedNames(group, me).first()

    @Test
    fun listsUnclaimedNamesWithTheirEvidence() = runTest {
        member(me.value)
        member("chelimo", placeholder = true)
        booked("chelimo", "e1", 2400)
        booked("chelimo", "e2", 1450)

        val name = names().single()
        assertEquals("chelimo", name.displayName)
        assertEquals(2, name.expenseCount)
        assertEquals(3850, name.owedSubunits)
        assertEquals("USD", name.currency)
        assertTrue(!name.multiCurrency)
    }

    @Test
    fun aNameWithNoExpensesIsStillOffered() = runTest {
        // It is still a candidate identity; the card just has no evidence to show for it.
        member(me.value)
        member("chelimo", placeholder = true)

        assertEquals(0, names().single().expenseCount)
    }

    @Test
    fun aNameSpanningCurrenciesReportsTheCountWithoutAnAmount() = runTest {
        // Summing across currencies is meaningless, so the amount is dropped rather than printed wrong.
        member(me.value)
        member("chelimo", placeholder = true)
        booked("chelimo", "e1", 2400, currency = "USD")
        booked("chelimo", "e2", 1450, currency = "MXN")

        val name = names().single()
        assertEquals(2, name.expenseCount)
        assertTrue(name.multiCurrency)
        assertNull(name.currency)
    }

    @Test
    fun aNameIcreatedIsNeverOfferedToMe() = runTest {
        member(me.value)
        member("chelimo", placeholder = true, createdBy = me.value)
        member("tyler", placeholder = true, createdBy = "maya")

        assertEquals(listOf("tyler"), names().map { it.displayName })
    }

    @Test
    fun aNameCreatedBeforeTheColumnExistedIsStillOfferedToEveryone() = runTest {
        member(me.value)
        member("chelimo", placeholder = true, createdBy = null)

        assertEquals(listOf("chelimo"), names().map { it.displayName })
    }

    @Test
    fun answeringNoRemovesExactlyThatNameAndSurvivesAReload() = runTest {
        member(me.value)
        member("chelimo", placeholder = true)
        member("tyler", placeholder = true)

        assertTrue(repo.answerNotMe(group, listOf(UserId("chelimo")), me) is AppResult.Ok)

        assertEquals(listOf("tyler"), names().map { it.displayName })
        // The answer is a synced row, not a device flag, so it is still there for a fresh read.
        assertEquals(1, db.placeholderClaimAnswerDao().answersOf(group.value, me.value).size)
    }

    @Test
    fun answeringNoTwiceIsOneRow() = runTest {
        // An offline "No" can be sent twice; the deterministic id makes the repeat a no-op.
        member(me.value)
        member("chelimo", placeholder = true)

        repo.answerNotMe(group, listOf(UserId("chelimo")), me)
        repo.answerNotMe(group, listOf(UserId("chelimo")), me)

        assertEquals(1, db.placeholderClaimAnswerDao().answersOf(group.value, me.value).size)
    }

    @Test
    fun noneOfTheseAreMeEmptiesTheList() = runTest {
        member(me.value)
        member("chelimo", placeholder = true)
        member("tyler", placeholder = true)

        repo.answerNotMe(group, names().map { it.userId }, me)

        assertTrue(names().isEmpty())
    }

    @Test
    fun aNameAddedAfterIansweredEverythingComesBack() = runTest {
        // The main reason answers are per name rather than per card.
        member(me.value)
        member("chelimo", placeholder = true)
        repo.answerNotMe(group, listOf(UserId("chelimo")), me)
        assertTrue(names().isEmpty())

        member("tyler", placeholder = true)

        assertEquals(listOf("tyler"), names().map { it.displayName })
    }

    @Test
    fun renamingANameIruledOutDoesNotBringItBack() = runTest {
        // Answers key on the placeholder's id, so a rename is invisible here by construction.
        member(me.value)
        member("chelimo", placeholder = true)
        repo.answerNotMe(group, listOf(UserId("chelimo")), me)

        db.userDao().updateDisplayName("chelimo", "Andrew C.", now = 99)

        assertTrue(names().isEmpty())
    }

    @Test
    fun aNameSomeoneElseClaimedDropsOutOfEveryList() = runTest {
        member(me.value)
        member("maya")
        member("chelimo", placeholder = true)
        booked("chelimo", "e1", 2400)

        assertTrue(repo.reconcilePlaceholder(group, UserId("chelimo"), UserId("maya")) is AppResult.Ok)

        assertTrue(names().isEmpty())
    }

    @Test
    fun realMembersAreNeverOffered() = runTest {
        member(me.value)
        member("maya")

        assertTrue(names().isEmpty())
    }
}
