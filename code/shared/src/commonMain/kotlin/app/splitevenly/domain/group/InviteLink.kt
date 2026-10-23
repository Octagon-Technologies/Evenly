package app.splitevenly.domain.group

/**
 * Builds and parses group invite links.
 *
 * The shared form is always a full `https://` URL. A bare `split-evenly.app/j/…` is not a link to a QR
 * scanner or most chat apps, so it was opened as a search query instead of a page. The same URL is an
 * iOS universal link and an Android App Link, so it opens the app when installed and the web join page
 * when not (`web/src/marketing/Join.svelte`).
 */
object InviteLink {
    const val HOST: String = "split-evenly.app"

    fun url(token: String): String = "https://$HOST/j/$token"

    /**
     * The invite token in [raw], or null when [raw] holds no invite. Finds the link anywhere in the text,
     * so pasting the whole share message works. Accepts the https form (with or without scheme or
     * `www.`), the legacy `splitevenly://j/<token>` deep link, and a bare token.
     */
    fun tokenFrom(raw: String): String? {
        val s = raw.trim()
        LINK.find(s)?.let { return it.groupValues[1] }
        return s.takeIf { BARE_TOKEN.matches(it) }
    }

    /** Invite tokens are UUIDs; the shape stays loose so a future token format still parses. */
    private val LINK =
        Regex(
            """(?:splitevenly://|(?:https?://)?(?:www\.)?split-evenly\.app/)j/([0-9A-Za-z-]{8,64})""",
            RegexOption.IGNORE_CASE,
        )
    private val BARE_TOKEN = Regex("[0-9A-Za-z-]{8,64}")
}
