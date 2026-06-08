package da.chelimo.sharecost.domain.group

import da.chelimo.sharecost.core.id.UserId

/**
 * A point-in-time view of a group member, carrying only what admin transfer needs.
 *
 * [joinedAt] is epoch millis — a smaller value means a longer-tenured member.
 * [isActive] is false for members who have left (`status = 'LEFT'`); only active
 * members are eligible to inherit admin.
 */
data class MemberSnapshot(
    val userId: UserId,
    val joinedAt: Long, // epoch millis — smaller = longer-tenured
    val isActive: Boolean,
)

/**
 * Determines who inherits admin when [leavingUserId] leaves the group — spec
 * 03-business-rules.md §7.5 and 01-glossary-and-domain-model.md §4.3.
 *
 * The next admin is the longest-tenured active member (smallest [MemberSnapshot.joinedAt]),
 * with ties broken by [UserId.value] ascending (D-28, consistent with the allocator).
 * The leaver and inactive members are excluded. Returns null when no eligible member
 * remains — the group is then abandoned (admin_user_id = NULL).
 */
fun determineNextAdmin(
    leavingUserId: UserId,
    members: List<MemberSnapshot>,
): UserId? =
    members
        .filter { it.isActive && it.userId != leavingUserId }
        .minWithOrNull(
            compareBy<MemberSnapshot> { it.joinedAt }.thenBy { it.userId.value },
        )
        ?.userId
