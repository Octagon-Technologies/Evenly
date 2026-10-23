package app.splitevenly.data.claim

import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.UserId
import app.splitevenly.platform.SecureStorage

/** A claim that has been started and is not finished: the name, who is taking it, and where. */
data class ParkedClaim(
    val groupId: GroupId,
    val placeholderUserId: UserId,
    val claimerUserId: UserId,
)

/**
 * The durable record of a claim that is mid-flight, so process death between the server-side guard and
 * the local merge can be finished later rather than stranding a person's whole history (finding R2).
 *
 * An interface rather than [SecureStorage] directly for two reasons: it keeps the coordinator's contract
 * about *claims* instead of about strings, and a Kotlin/Native test process has no usable Keychain, so a
 * real store would make the one thing worth testing here untestable.
 */
interface PendingClaimStore {
    suspend fun park(claim: ParkedClaim)

    suspend fun clear(
        groupId: GroupId,
        placeholderUserId: UserId,
    )

    suspend fun parked(): List<ParkedClaim>
}

/**
 * [SecureStorage]-backed [PendingClaimStore]: device-local, never synced, dies on sign-out.
 *
 * One record per line, `groupId|placeholderUserId|claimerUserId`. All three are uuids, so nothing needs
 * escaping, and a record that will not parse is dropped rather than retried forever.
 *
 * Room would have been the other option and is deliberately not used: this is a receipt for one
 * in-flight action on this phone, and it must never ride the sync push.
 */
class SecureStoragePendingClaims(
    private val storage: SecureStorage,
) : PendingClaimStore {
    override suspend fun park(claim: ParkedClaim) {
        val kept = parked().filterNot { it.key() == claim.key() }
        write(kept + claim)
    }

    override suspend fun clear(
        groupId: GroupId,
        placeholderUserId: UserId,
    ) {
        write(parked().filterNot { it.groupId == groupId && it.placeholderUserId == placeholderUserId })
    }

    override suspend fun parked(): List<ParkedClaim> =
        storage
            .getString(KEY)
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size == RECORD_FIELDS && parts.none { it.isEmpty() }) {
                    ParkedClaim(GroupId(parts[0]), UserId(parts[1]), UserId(parts[2]))
                } else {
                    null
                }
            }.toList()

    private suspend fun write(claims: List<ParkedClaim>) {
        if (claims.isEmpty()) {
            storage.remove(KEY)
        } else {
            storage.putString(
                KEY,
                claims.joinToString("\n") {
                    "${it.groupId.value}|${it.placeholderUserId.value}|${it.claimerUserId.value}"
                },
            )
        }
    }

    private fun ParkedClaim.key() = groupId.value to placeholderUserId.value

    private companion object {
        const val KEY = "placeholder_claim_pending"

        /** groupId, placeholderUserId, claimerUserId. */
        const val RECORD_FIELDS = 3
    }
}
