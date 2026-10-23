package app.splitevenly.domain.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteLinkParseTest {
    private val token = "01a16973-8c78-7001-9dd8-3bdbc21b7339"

    @Test
    fun sharedUrlHasAScheme() {
        // Without https:// QR scanners and Safari treated the link as a search query.
        val url = InviteLink.url(token)
        assertTrue(url.startsWith("https://"))
        assertEquals(token, InviteLink.tokenFrom(url))
    }

    @Test
    fun parsesEveryFormPeopleActuallyHave() {
        listOf(
            "https://split-evenly.app/j/$token",
            "https://www.split-evenly.app/j/$token/",
            "split-evenly.app/j/$token",
            "splitevenly://j/$token",
            "https://split-evenly.app/j/$token?utm_source=x",
            "Join my group on Evenly: https://split-evenly.app/j/$token",
            "  $token  ",
        ).forEach { assertEquals(token, InviteLink.tokenFrom(it), it) }
    }

    @Test
    fun rejectsOtherLinks() {
        assertNull(InviteLink.tokenFrom("https://split-evenly.app/b/AAAAAAAAAAAAAAAAAAAA"))
        assertNull(InviteLink.tokenFrom("https://evil.example/j/$token"))
        assertNull(InviteLink.tokenFrom("splitevenly://login-callback#access_token=abc"))
        assertNull(InviteLink.tokenFrom(""))
    }
}
