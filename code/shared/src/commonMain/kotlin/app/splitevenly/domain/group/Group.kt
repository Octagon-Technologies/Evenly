package app.splitevenly.domain.group

import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId

/**
 * Domain view of a group (02 §3.4). Pure Kotlin — no Room types cross the repository boundary
 * (06 §3). [adminUserId] is null when the group is abandoned (zero active members, AC-INV-005).
 */
data class Group(
    val id: GroupId,
    val name: String,
    val emoji: String,
    val baseCurrency: String,
    val adminUserId: UserId?,
    val inviteToken: String,
    val createdAt: Long,
    /**
     * Set only when the group is in Recently deleted. Every stream but
     * [app.splitevenly.domain.repository.GroupRepository.observeGroupIncludingDeleted] filters these
     * out, so a non-null value here means the caller deliberately asked to see the tombstone.
     */
    val deletedAt: Long? = null,
    val deletedBy: UserId? = null,
)

/**
 * Input to [app.splitevenly.domain.repository.GroupRepository.createGroup]. The creator becomes
 * the first member and admin; [baseCurrency] defaults to the creator's currency at the call site
 * (D-27) — here it is explicit.
 */
data class NewGroup(
    val name: String,
    val baseCurrency: String,
    val creatorUserId: UserId,
    val emoji: String = "💸",
)
