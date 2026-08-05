package app.splitevenly.ui.screen.group

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for the invite QR.
 *
 * The QR is only drawn once a real join link exists. That test used to match the brand prefix
 * ("sharecost"), which the Evenly rebrand silently falsified: the link moved to split-evenly.app
 * while the guard moved to "splitevenly", so the two stopped agreeing and the QR quietly vanished.
 * Nothing failed, because a Composable that renders nothing still compiles and still passes.
 *
 * These cases pin the guard to the token path segment, so changing the brand or the domain again
 * cannot take the QR down with it.
 */
class InviteLinkTest {

    @Test
    fun realInviteLinkShowsTheQr() {
        assertTrue(hasInviteToken("split-evenly.app/j/8Kk2-Tulum"))
    }

    @Test
    fun theGeneratingPlaceholderDoesNot() {
        assertFalse(hasInviteToken("Generating link…"))
    }

    @Test
    fun survivesADomainChange() {
        // The whole point: a future host swap must not silently disable the QR again.
        assertTrue(hasInviteToken("evenly.com/j/8Kk2-Tulum"))
        assertTrue(hasInviteToken("https://some-other-domain.example/j/8Kk2-Tulum"))
        assertTrue(hasInviteToken("sharecost.app/j/8Kk2-Tulum"))
    }
}
