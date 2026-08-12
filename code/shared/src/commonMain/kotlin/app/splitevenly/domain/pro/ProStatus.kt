package app.splitevenly.domain.pro

/** Which of the two doors to Evenly Pro answered (`PRO_PASS_SPEC.md` §5.3). */
enum class ProSource {
    /** A one-off pass bought for this group. Ends on its own; nothing to cancel. */
    Pass,

    /** An active member's personal auto-renewing subscription, which covers every group they are in. */
    Subscription,
}

/**
 * Whether a group is on Evenly Pro right now, and what says so (`PRO_PASS_SPEC.md` §5.3).
 *
 * Every field but [isPro] is null exactly when [isPro] is false.
 */
data class ProStatus(
    val isPro: Boolean,
    val expiresAt: Long? = null,
    val purchasedBy: String? = null,
    /** The pass tier (`week_1`…) or the subscription period (`monthly` | `annual`). */
    val tier: String? = null,
    val source: ProSource? = null,
) {
    companion object {
        val Free = ProStatus(isPro = false)
    }
}

/**
 * One thing that could make a group Pro, reduced to the fields the rule below reads. Keeps the rule free
 * of Room, and mirrors the server's `union all` of the two routes: passes and subscriptions arrive here
 * flattened into one list precisely so neither the rule nor its callers grow a branch per route.
 */
data class ProCandidate(
    val expiresAt: Long,
    /** `users.id` of whoever is paying: the buyer of a pass, or the subscriber. */
    val purchasedBy: String,
    val tier: String,
    val source: ProSource,
    val revokedAt: Long? = null,
)

/**
 * The client's mirror of the server's `group_pro_status` SQL function.
 *
 * Two implementations of one rule is a real wart, taken on so that rendering a Pro badge costs no
 * network round trip and works offline. **They must stay in step**, which is why both sides are pinned
 * by the same cases (`ProStatusTest` here, the transactional vector run on the SQL side). Enforcement is
 * always the server's copy; this one only ever decides what to draw.
 *
 * Returns the LATEST-EXPIRING live candidate, which is what makes stacking work without special-casing:
 * buying while Pro inserts a pass starting at the current expiry, so two friends who each buy a week
 * give the group two weeks and the later expiry is simply the answer. The same ordering settles a pass
 * and a subscription overlapping, and [ProStatus.source] then says which one the group is riding on.
 *
 * [now] `>= expiresAt` is expired. An entitlement whose expiry is exactly now has run out, and the
 * boundary is stated here rather than left to whichever comparison a caller happens to write.
 */
fun proStatusOf(candidates: List<ProCandidate>, now: Long): ProStatus {
    val live = candidates
        .filter { it.revokedAt == null && it.expiresAt > now }
        .maxByOrNull { it.expiresAt }
        ?: return ProStatus.Free
    return ProStatus(
        isPro = true,
        expiresAt = live.expiresAt,
        purchasedBy = live.purchasedBy,
        tier = live.tier,
        source = live.source,
    )
}
