package app.splitevenly.domain.group

import app.splitevenly.core.id.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [determineNextAdmin] (spec 03-business-rules.md §7.5,
 * 01-glossary-and-domain-model.md §4.3, AC-M1-015 / AC-M1-016).
 *
 * When the admin leaves, admin transfers to the longest-tenured active member
 * (smallest [MemberSnapshot.joinedAt]). Ties broken by [UserId.value] ascending
 * (D-28, consistent with the allocator). Inactive members and the leaver are
 * never eligible. Returns null when no eligible member remains (abandonment).
 */
class AdminTransferTest {

    private fun member(id: String, joinedAt: Long, isActive: Boolean = true) =
        MemberSnapshot(userId = UserId(id), joinedAt = joinedAt, isActive = isActive)

    // AC-M1-015: [A(admin, day1), B(day2), C(day3)] — A leaves → B (longest-tenured remaining).
    @Test
    fun acM1015_adminLeaves_longestTenuredRemainingBecomesAdmin() {
        val a = member("a", joinedAt = 1L)
        val b = member("b", joinedAt = 2L)
        val c = member("c", joinedAt = 3L)

        val next = determineNextAdmin(leavingUserId = a.userId, members = listOf(a, b, c))

        assertEquals(b.userId, next)
    }

    // AC-M1-016: only member A leaves → null (group becomes abandoned, no admin).
    @Test
    fun acM1016_onlyMemberLeaves_noAdmin() {
        val a = member("a", joinedAt = 1L)

        val next = determineNextAdmin(leavingUserId = a.userId, members = listOf(a))

        assertNull(next)
    }

    // Same joinedAt → tiebreak on UserId.value ascending: "b" < "c", so "b" wins.
    @Test
    fun sameJoinedAt_lowerUserIdWins() {
        val a = member("a", joinedAt = 1L)
        val c = member("c", joinedAt = 5L)
        val b = member("b", joinedAt = 5L)

        val next = determineNextAdmin(leavingUserId = a.userId, members = listOf(a, c, b))

        assertEquals(b.userId, next)
    }

    // Inactive members are excluded even if longer-tenured: B(day2, inactive), A leaves → C(day3).
    @Test
    fun inactiveMemberExcluded_evenIfLongerTenured() {
        val a = member("a", joinedAt = 1L)
        val b = member("b", joinedAt = 2L, isActive = false)
        val c = member("c", joinedAt = 3L)

        val next = determineNextAdmin(leavingUserId = a.userId, members = listOf(a, b, c))

        assertEquals(c.userId, next)
    }

    // The leaver is excluded even if they are the longest-tenured active member.
    @Test
    fun leaverExcluded_evenIfLongestTenured() {
        val a = member("a", joinedAt = 1L)
        val b = member("b", joinedAt = 2L)

        val next = determineNextAdmin(leavingUserId = a.userId, members = listOf(a, b))

        assertEquals(b.userId, next)
    }

    // All remaining members inactive → null (no eligible admin).
    @Test
    fun allRemainingInactive_noAdmin() {
        val a = member("a", joinedAt = 1L)
        val b = member("b", joinedAt = 2L, isActive = false)

        val next = determineNextAdmin(leavingUserId = a.userId, members = listOf(a, b))

        assertNull(next)
    }
}
