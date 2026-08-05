package app.splitevenly.domain.expense

import app.splitevenly.core.id.UserId

/**
 * The payer's view of a bill's web claim link (WEB_CLAIM_SPEC.md §2.9, §3.9.3, E26–E30).
 *
 * Two links exist in this product and are kept apart on purpose: this one is **per-expense, 72 hours,
 * claim-only**; the group invite is permanent and needs an account. Do not merge them.
 *
 * ## Why [url] can be null while [exists] is true
 *
 * The token is minted server-side and returned exactly once, hashed at rest (§4.2). The device that
 * created the link keeps the plaintext locally; nothing can hand it back afterwards. So a payer opening
 * this screen on a second device sees a live link it cannot render a QR for. That is the honest state,
 * not an error: the fix on offer is "make a new link", which rotates the token and revokes the old one.
 */
data class WebBillLinkState(
    /** A link row exists for this bill (live, expired, or revoked). */
    val exists: Boolean,
    /** `split-evenly.app/b/<token>`, or null when this device does not hold the plaintext. */
    val url: String?,
    val createdAt: Long?,
    val expiresAt: Long?,
    val revokedAt: Long?,
    val extendedCount: Int,
    /** Newest web-session activity per participant, in this group. See [openedThisLink]. */
    val openedAtByUser: Map<UserId, Long>,
) {
    fun isLive(now: Long): Boolean =
        exists && revokedAt == null && (expiresAt ?: 0L) > now

    /**
     * Whether this person has opened *this* link, rather than merely having a session in the group from
     * some earlier bill. Sessions are group-scoped and durable (§2.2), so the comparison has to be
     * against when this link was minted, which also makes a re-issued link reset everyone to "hasn't
     * opened" with no extra server concept.
     */
    fun openedThisLink(userId: UserId): Boolean {
        val seen = openedAtByUser[userId] ?: return false
        return seen >= (createdAt ?: Long.MAX_VALUE)
    }

    companion object {
        /** Nothing has ever been shared for this bill. */
        val None = WebBillLinkState(false, null, null, null, null, 0, emptyMap())
    }
}

/**
 * Testing switch, same shape as [app.splitevenly.data.remote.supabase.SupabaseConfig]'s `USE_LOCAL`.
 * While true the QR/share link points at the `web/` Vite dev server (`npm run dev`, port 5177) instead
 * of the production domain, so step-3 claiming can be exercised before split-evenly.app is live on
 * Netlify. `127.0.0.1` is correct for the iOS Simulator (shares the host network); swap it for
 * `10.0.2.2` on the Android emulator or `http://<lan-ip>:5177` on a physical device.
 *
 * **Flip this back to false before shipping.** Left true, the payer holds up a QR that resolves to
 * nothing on anyone else's phone, and there is no error to notice: it looks like a working link. Prose
 * does not hold that, so `.github/workflows/release-guards.yml` fails the build on `main` and on any PR
 * targeting it while this is true. Feature branches are unaffected, which is the point.
 */
private const val USE_LOCAL_WEB_CLAIM_HOST: Boolean = false
private const val LOCAL_WEB_CLAIM_HOST: String = "http://127.0.0.1:5177"
private const val PROD_WEB_CLAIM_HOST: String = "split-evenly.app"

/**
 * The claim URL for a bill token (spec §3.1 — `/b/:token`). Deliberately the same shape and host as the
 * group invite's `/j/<token>`, and deliberately a *different path*: §2.9 keeps the two links apart
 * because they have different scopes, lifetimes and account requirements.
 */
fun billClaimUrl(token: String): String {
    val host = if (USE_LOCAL_WEB_CLAIM_HOST) LOCAL_WEB_CLAIM_HOST else PROD_WEB_CLAIM_HOST
    return "$host/b/$token"
}

/** How far along a person is on a shared bill, for the payer's "who's still to claim" screen (§3.9.2). */
enum class ClaimProgressState {
    /** Has claimed at least one line (whether or not they tapped "I'm done"). */
    CLAIMED,

    /** A live web session opened since this link was minted, but nothing claimed yet. */
    OPENED_NOTHING_CLAIMED,

    /** No sign of them on the web at all. Only honest for someone without the app. */
    NOT_OPENED,

    /** They have Evenly; the link is not how they were going to claim. */
    APP_MEMBER_NOT_CLAIMED,
}
