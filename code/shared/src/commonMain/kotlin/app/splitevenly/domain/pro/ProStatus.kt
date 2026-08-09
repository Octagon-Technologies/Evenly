package app.splitevenly.domain.pro

/**
 * Whether a group is on Evenly Pro right now, and which pass says so (`PRO_PASS_SPEC.md` §5.2).
 *
 * [expiresAt] and [purchasedBy] are null exactly when [isPro] is false.
 */
data class ProStatus(
    val isPro: Boolean,
    val expiresAt: Long? = null,
    val purchasedBy: String? = null,
    val tier: String? = null,
) {
    companion object {
        val Free = ProStatus(isPro = false)
    }
}

/** One pass, reduced to the four fields the rule below reads. Keeps the rule free of Room. */
data class ProPass(
    val expiresAt: Long,
    val purchasedBy: String,
    val tier: String,
    val revokedAt: Long? = null,
)

/**
 * The client's mirror of the server's `group_pro_status` SQL function.
 *
 * Two implementations of one rule is a real wart, taken on so that rendering a Pro badge costs no
 * network round trip and works offline. **They must stay in step**, which is why both sides are pinned
 * by the same cases (`ProStatusTest` here, the transactional check on the SQL side). Enforcement is
 * always the server's copy; this one only ever decides what to draw.
 *
 * Returns the LATEST-EXPIRING live pass, which is what makes stacking work without special-casing:
 * buying while Pro inserts a pass starting at the current expiry, so two friends who each buy a week
 * give the group two weeks and the later expiry is simply the answer.
 *
 * [now] `>= expiresAt` is expired. A pass whose expiry is exactly now has run out, and the boundary is
 * stated here rather than left to whichever comparison a caller happens to write.
 */
fun proStatusOf(passes: List<ProPass>, now: Long): ProStatus {
    val live = passes
        .filter { it.revokedAt == null && it.expiresAt > now }
        .maxByOrNull { it.expiresAt }
        ?: return ProStatus.Free
    return ProStatus(
        isPro = true,
        expiresAt = live.expiresAt,
        purchasedBy = live.purchasedBy,
        tier = live.tier,
    )
}
