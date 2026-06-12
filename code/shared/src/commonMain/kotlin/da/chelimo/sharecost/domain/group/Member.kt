package da.chelimo.sharecost.domain.group

import da.chelimo.sharecost.core.id.UserId

/**
 * A group member joined to its user for display (02 §3.5 + §3.2). [displayName] is null only when
 * the user row has not synced yet (the join is tolerant so members never silently vanish from a
 * roster). Placeholders ([isPlaceholder]) participate in splits but never sign in.
 */
data class Member(
    val userId: UserId,
    val displayName: String?,
    val isPlaceholder: Boolean,
    val isAdmin: Boolean,
    val joinedAt: Long,
)
