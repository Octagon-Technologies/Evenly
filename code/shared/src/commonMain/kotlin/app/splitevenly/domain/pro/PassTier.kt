package app.splitevenly.domain.pro

/**
 * The three group passes (`PRO_PASS_SPEC.md` §2.3), keyed by their RevenueCat package id.
 *
 * [days] is **display only**: the server stamps the real expiry on its own clock, because a phone with
 * its clock wound back would otherwise buy a week and get a decade. This table exists so the sheet can
 * say "Extend to 23 Aug" *before* the charge, which is the whole point of the stacking screen.
 *
 * Ordering here is a fallback for rendering, not a source of truth. The offering drives which passes
 * exist and what they cost, or a pricing change stops being a dashboard change (§9).
 */
enum class PassTier(val packageId: String, val tier: String, val days: Int) {
    Week1("pass_week_1", "week_1", 7),
    Week2("pass_week_2", "week_2", 14),
    Month1("pass_month_1", "month_1", 30),
    ;

    companion object {
        fun byPackageId(packageId: String): PassTier? = entries.firstOrNull { it.packageId == packageId }

        private const val DAY_MILLIS = 86_400_000L

        /**
         * When a group would be Pro until, if this pass were bought now.
         *
         * Stacking is [currentExpiresAt] `?: now` as the start, never [now] alone (§5.4): a pass bought
         * while the group is already Pro extends from the current expiry, so two friends who each buy a
         * week give the group two weeks rather than overlapping and wasting one of them.
         */
        fun expiryIfBought(tier: PassTier, now: Long, currentExpiresAt: Long?): Long =
            maxOf(now, currentExpiresAt ?: now) + tier.days * DAY_MILLIS
    }
}
