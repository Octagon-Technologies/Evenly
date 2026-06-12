package da.chelimo.sharecost.domain.group

import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.UserId

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
)

/**
 * Input to [da.chelimo.sharecost.domain.repository.GroupRepository.createGroup]. The creator becomes
 * the first member and admin; [baseCurrency] defaults to the creator's currency at the call site
 * (D-27) — here it is explicit.
 */
data class NewGroup(
    val name: String,
    val baseCurrency: String,
    val creatorUserId: UserId,
    val emoji: String = "💸",
)
