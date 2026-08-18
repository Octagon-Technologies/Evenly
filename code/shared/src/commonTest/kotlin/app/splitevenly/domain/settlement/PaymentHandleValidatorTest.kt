package app.splitevenly.domain.settlement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vectors for [checkPaymentHandle] and friends. Every rule in the provider docs gets an accept case
 * and a reject case, because a validator that only rejects is a validator nobody can satisfy.
 */
class PaymentHandleValidatorTest {
    private fun settled(
        app: PaymentApp,
        value: String,
    ) = checkPaymentHandle(app, value, HandleStage.SETTLED)

    private fun typing(
        app: PaymentApp,
        value: String,
    ) = checkPaymentHandle(app, value, HandleStage.TYPING)

    // --- tier 1: cleaned silently, never reported -------------------------------------------------

    @Test
    fun pastedProfileUrlsAreStrippedToTheHandle() {
        assertEquals("alex-rivera", stripHandleNoise("https://venmo.com/u/alex-rivera"))
        assertEquals("alex-rivera", stripHandleNoise("  https://www.venmo.com/u/alex-rivera/  "))
        assertEquals("\$alexr", stripHandleNoise("https://cash.app/\$alexr"))
        assertEquals("alexrivera", stripHandleNoise("paypal.me/alexrivera?country.x=US"))
        assertEquals("alexrivera", stripHandleNoise("https://www.paypal.com/paypalme/alexrivera#top"))
    }

    @Test
    fun canonicalFormCarriesExactlyOneSigil() {
        assertEquals("@alexr", canonicalPaymentHandle(PaymentApp.VENMO, "alexr"))
        assertEquals("@alexr", canonicalPaymentHandle(PaymentApp.VENMO, "@alexr"))
        assertEquals("@alexr", canonicalPaymentHandle(PaymentApp.VENMO, "@@alexr"))
        assertEquals("@alexr", canonicalPaymentHandle(PaymentApp.VENMO, "https://venmo.com/u/alexr"))
        assertEquals("\$alexr", canonicalPaymentHandle(PaymentApp.CASH_APP, "alexr"))
        assertEquals("\$alexr", canonicalPaymentHandle(PaymentApp.CASH_APP, "\$alexr"))
        assertEquals("alexrivera", canonicalPaymentHandle(PaymentApp.PAYPAL, "paypal.me/alexrivera"))
    }

    // A sigil with nothing after it clears the handle rather than storing a lone "@".
    @Test
    fun sigilOnlyCanonicalisesToBlank() {
        assertEquals("", canonicalPaymentHandle(PaymentApp.VENMO, "@"))
        assertEquals("", canonicalPaymentHandle(PaymentApp.CASH_APP, "\$"))
        assertEquals("", canonicalPaymentHandle(PaymentApp.PAYPAL, "  "))
    }

    // The canonical form has to survive the round trip the UI does every keystroke: the field holds
    // the body, the store holds the sigil + body.
    @Test
    fun bodyAndCanonicalRoundTrip() {
        for (app in PaymentApp.entries) {
            val canonical = canonicalPaymentHandle(app, "alexr")
            assertEquals(canonical, canonicalPaymentHandle(app, paymentHandleBody(app, canonical)))
        }
    }

    // --- Venmo: 5-30 of [A-Za-z0-9_-] -------------------------------------------------------------

    @Test
    fun venmoAcceptsLettersDigitsHyphenUnderscore() {
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.VENMO, "@alex-rivera"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.VENMO, "alex_r99"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.VENMO, "12345"))
    }

    @Test
    fun venmoRejectsOtherPunctuation() {
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.VENMO, "alex.rivera"))
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.VENMO, "alex rivera"))
    }

    @Test
    fun venmoEnforcesItsLengthBounds() {
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.VENMO, "alex"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.VENMO, "alexr"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.VENMO, "a".repeat(30)))
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.VENMO, "a".repeat(31)))
    }

    // Venmo publishes no "must start with a letter" rule, so we must not invent one.
    @Test
    fun venmoAllowsALeadingDigit() {
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.VENMO, "9lives-cat"))
    }

    // --- Cash App: 1-20 alphanumerics, at least one letter ----------------------------------------

    @Test
    fun cashAppAcceptsAlphanumerics() {
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.CASH_APP, "\$alexr"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.CASH_APP, "alexr99"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.CASH_APP, "a"))
    }

    @Test
    fun cashAppRejectsSymbolsAndOverLength() {
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.CASH_APP, "\$alex.rivera"))
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.CASH_APP, "alex rivera"))
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.CASH_APP, "a".repeat(21)))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.CASH_APP, "a".repeat(20)))
    }

    @Test
    fun cashAppNeedsAtLeastOneLetter() {
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.CASH_APP, "\$12345"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.CASH_APP, "\$1234a"))
    }

    // A hyphen is legal for Venmo and not for Cash App, so the error says where it probably belongs.
    @Test
    fun cashAppNamesVenmoWhenTheValueIsHyphenated() {
        val verdict = settled(PaymentApp.CASH_APP, "alex-rivera")
        assertIs<HandleVerdict.Invalid>(verdict)
        assertTrue(verdict.message.contains("Venmo"))
    }

    // --- PayPal: 1-20 alphanumerics ---------------------------------------------------------------

    @Test
    fun payPalAcceptsAlphanumericsOnly() {
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.PAYPAL, "alexrivera"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.PAYPAL, "paypal.me/AlexR99"))
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.PAYPAL, "alex-rivera"))
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.PAYPAL, "a".repeat(21)))
    }

    // --- Zelle: an enrolled email or a US mobile --------------------------------------------------

    @Test
    fun zelleAcceptsEmailAndUsMobile() {
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.ZELLE, "alex@rivera.com"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.ZELLE, "alex.rivera+bank@sub.example.co.uk"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.ZELLE, "(415) 555-0132"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.ZELLE, "+1 415 555 0132"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.ZELLE, "4155550132"))
    }

    @Test
    fun zelleRejectsTollFreeNumbers() {
        val verdict = settled(PaymentApp.ZELLE, "1-800-555-0132")
        assertIs<HandleVerdict.Invalid>(verdict)
        assertTrue(verdict.message.contains("Toll-free"))
    }

    @Test
    fun zelleRejectsMalformedNumbersAndNonAddresses() {
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.ZELLE, "0155550132")) // area code can't start 0
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.ZELLE, "4151550132")) // exchange can't start 1
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.ZELLE, "+44 20 7946 0958")) // not US
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.ZELLE, "alex@rivera"))
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.ZELLE, "just-a-name"))
    }

    // --- the ordering trap ------------------------------------------------------------------------

    // In the Zelle field an '@' is an email, not a stray Venmo name. Email has to be tested before the
    // '@'-means-Venmo rule or every Zelle address reports as misrouted.
    @Test
    fun anAtSignInTheZelleFieldIsAnEmailNotVenmo() {
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.ZELLE, "alex@rivera.com"))
        assertEquals(PaymentApp.ZELLE, detectPaymentApp("alex@rivera.com"))
    }

    // --- misroute detection -----------------------------------------------------------------------

    @Test
    fun aCashtagInThePayPalFieldNamesCashApp() {
        val verdict = settled(PaymentApp.PAYPAL, "\$alexr")
        assertIs<HandleVerdict.WrongApp>(verdict)
        assertEquals(PaymentApp.CASH_APP, verdict.belongsTo)
        assertEquals("\$alexr", verdict.canonical)
    }

    @Test
    fun everyFingerprintRoutesToItsService() {
        assertEquals(PaymentApp.CASH_APP, detectPaymentApp("\$alexr"))
        assertEquals(PaymentApp.VENMO, detectPaymentApp("@alex-rivera"))
        assertEquals(PaymentApp.PAYPAL, detectPaymentApp("paypal.me/alexrivera"))
        assertEquals(PaymentApp.PAYPAL, detectPaymentApp("https://www.paypal.com/paypalme/alexr"))
        assertEquals(PaymentApp.VENMO, detectPaymentApp("https://venmo.com/u/alex-rivera"))
        assertEquals(PaymentApp.CASH_APP, detectPaymentApp("cash.app/\$alexr"))
        assertEquals(PaymentApp.ZELLE, detectPaymentApp("alex@rivera.com"))
        assertEquals(PaymentApp.ZELLE, detectPaymentApp("(415) 555-0132"))
    }

    // A bare handle carries no fingerprint, so nothing is claimed about it.
    @Test
    fun anUnmarkedHandleIsNotRoutedAnywhere() {
        assertNull(detectPaymentApp("alexrivera"))
        assertNull(detectPaymentApp("alex-rivera"))
        assertNull(detectPaymentApp(""))
    }

    // A run of digits is a legal Venmo username as well as a phone number. Routing it to Zelle on
    // sight would nag someone whose handle happens to be numeric.
    @Test
    fun bareDigitsAreNotAssumedToBeAPhoneNumber() {
        assertNull(detectPaymentApp("4155550132"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.VENMO, "4155550132"))
        assertEquals(PaymentApp.ZELLE, detectPaymentApp("415-555-0132"))
    }

    // The UI canonicalises whatever is typed, so a foreign sigil ends up UNDER our own: "$alexr" in the
    // Venmo field is stored "@$alexr". Testing only the outer sigil would report a generic
    // illegal-character error instead of naming Cash App.
    @Test
    fun aForeignSigilIsFoundUnderneathOurOwn() {
        val verdict = settled(PaymentApp.VENMO, canonicalPaymentHandle(PaymentApp.VENMO, "\$alexr"))
        assertIs<HandleVerdict.WrongApp>(verdict)
        assertEquals(PaymentApp.CASH_APP, verdict.belongsTo)
        assertEquals("\$alexr", verdict.canonical)
    }

    // A value already in the right field is never reported as misrouted.
    @Test
    fun theRightFieldIsNeverAMisroute() {
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.CASH_APP, "\$alexr"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.VENMO, "@alex-rivera"))
        assertIs<HandleVerdict.Ok>(settled(PaymentApp.PAYPAL, "paypal.me/alexrivera"))
    }

    // --- staging: what waits until the user stops typing ------------------------------------------

    @Test
    fun anIncompleteValueIsHeldBackWhileTyping() {
        assertIs<HandleVerdict.Pending>(typing(PaymentApp.VENMO, "al"))
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.VENMO, "al"))
        assertIs<HandleVerdict.Pending>(typing(PaymentApp.CASH_APP, "12"))
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.CASH_APP, "12"))
        assertIs<HandleVerdict.Pending>(typing(PaymentApp.ZELLE, "alex@"))
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.ZELLE, "alex@"))
    }

    // A problem more typing cannot fix interrupts immediately, in either stage.
    @Test
    fun anUnfixableProblemInterruptsImmediately() {
        assertIs<HandleVerdict.Invalid>(typing(PaymentApp.VENMO, "alex.r"))
        assertIs<HandleVerdict.Invalid>(typing(PaymentApp.CASH_APP, "a".repeat(21)))
        assertIs<HandleVerdict.WrongApp>(typing(PaymentApp.PAYPAL, "\$alexr"))
    }

    @Test
    fun blankIsNeverAProblem() {
        for (app in PaymentApp.entries) {
            assertIs<HandleVerdict.Empty>(settled(app, ""))
            assertIs<HandleVerdict.Empty>(settled(app, "   "))
        }
    }

    // --- the save gate ----------------------------------------------------------------------------

    @Test
    fun saveIsBlockedByAnInvalidHandleAndNotByAMisroute() {
        assertTrue(paymentHandlesAreSaveable(emptyMap()))
        assertTrue(paymentHandlesAreSaveable(mapOf(PaymentApp.VENMO to "@alex-rivera", PaymentApp.ZELLE to "")))
        // A misroute is a suggestion, not a defect: the user may know something we do not.
        assertTrue(paymentHandlesAreSaveable(mapOf(PaymentApp.PAYPAL to "\$alexr")))
        assertFalse(paymentHandlesAreSaveable(mapOf(PaymentApp.VENMO to "alex.rivera")))
    }

    // --- the preferred slot -----------------------------------------------------------------------

    @Test
    fun theFirstHandleAddedBecomesThePreferredOne() {
        val one = mapOf(PaymentApp.CASH_APP to "\$alexr")
        assertEquals(PaymentApp.CASH_APP, resolvePreferredPaymentApp(one, current = null))
        // A second one does not take the slot off the first.
        val two = one + (PaymentApp.VENMO to "@alex-rivera")
        assertEquals(PaymentApp.CASH_APP, resolvePreferredPaymentApp(two, current = PaymentApp.CASH_APP))
    }

    @Test
    fun removingThePreferredHandlePromotesTheNextOne() {
        val two = mapOf(PaymentApp.CASH_APP to "\$alexr", PaymentApp.VENMO to "@alex-rivera")
        val cleared = two + (PaymentApp.CASH_APP to "")
        assertEquals(PaymentApp.VENMO, resolvePreferredPaymentApp(cleared, current = PaymentApp.CASH_APP))
    }

    // Clearing and refilling is not "adding it again": the map keeps the key in place, so the handle
    // that was there first is still the one that was there first.
    @Test
    fun refillingAClearedHandleDoesNotStealTheSlotBack() {
        val two = mapOf(PaymentApp.CASH_APP to "\$alexr", PaymentApp.VENMO to "@alex-rivera")
        val cleared = two + (PaymentApp.CASH_APP to "")
        val promoted = resolvePreferredPaymentApp(cleared, current = PaymentApp.CASH_APP)
        val refilled = cleared + (PaymentApp.CASH_APP to "\$alexr")
        assertEquals(PaymentApp.VENMO, resolvePreferredPaymentApp(refilled, current = promoted))
    }

    @Test
    fun anExplicitChoiceSurvivesLaterEdits() {
        val two = mapOf(PaymentApp.CASH_APP to "\$alexr", PaymentApp.VENMO to "@alex-rivera")
        val three = two + (PaymentApp.PAYPAL to "alexrivera")
        assertEquals(PaymentApp.VENMO, resolvePreferredPaymentApp(three, current = PaymentApp.VENMO))
    }

    @Test
    fun noHandlesMeansNoPreferredApp() {
        assertNull(resolvePreferredPaymentApp(emptyMap(), current = null))
        assertNull(resolvePreferredPaymentApp(mapOf(PaymentApp.VENMO to ""), current = PaymentApp.VENMO))
    }

    // --- ASCII, not Unicode -----------------------------------------------------------------------

    // Kotlin's isLetterOrDigit() is Unicode-aware and every provider rule here is an ASCII rule.
    @Test
    fun unicodeLettersAndDigitsAreRejected() {
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.VENMO, "andré-r"))
        assertIs<HandleVerdict.Invalid>(settled(PaymentApp.CASH_APP, "alex９９"))
    }

    // --- the canonical form the deep-link templates depend on -------------------------------------

    // buildDeepLink interpolates the Cash App handle straight after "cash.app/", so a stored handle
    // that lost its '$' produces a dead link. This is the seam between the two files.
    @Test
    fun canonicalHandlesProduceTheExpectedDeepLinks() {
        val cashTag = canonicalPaymentHandle(PaymentApp.CASH_APP, "alexr")
        assertEquals(
            "https://cash.app/\$alexr/12.34",
            buildDeepLink(PaymentApp.CASH_APP, cashTag, 1234L, "Trip", "Dinner").url,
        )
        val payPal = canonicalPaymentHandle(PaymentApp.PAYPAL, "paypal.me/alexrivera")
        assertEquals(
            "https://paypal.me/alexrivera/12.34USD",
            buildDeepLink(PaymentApp.PAYPAL, payPal, 1234L, "Trip", "Dinner").url,
        )
    }
}
