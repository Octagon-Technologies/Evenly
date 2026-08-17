// Guard clauses are the readable shape for a table of format rules, and the file is one such table:
// four services times normalize/detect/check is 20 small functions, not a class doing 20 things.
@file:Suppress("ReturnCount", "TooManyFunctions")

package app.splitevenly.domain.settlement

/**
 * Format checks for the payment handles other members deep-link into (03 §5.1).
 *
 * Rules come from each provider's own documentation:
 * - **Venmo** 5-30 characters of `[A-Za-z0-9_-]`. Venmo publishes no "must start with a letter" rule,
 *   so none is enforced here.
 * - **Cash App** 1-20 alphanumerics containing at least one letter; symbols, dashes and spaces are
 *   rejected outright.
 * - **PayPal** 1-20 alphanumerics for the PayPal.Me link name; no symbols, dashes or spaces.
 * - **Zelle** not a username at all: an enrolled email address or a US mobile number. Toll-free
 *   prefixes cannot enroll.
 *
 * **The canonical stored form carries the service's own sigil** (`@alex-rivera`, `$alexr`).
 * That is not cosmetic: [buildDeepLink]'s Cash App template interpolates the handle directly after
 * `cash.app/`, so the `$` has to be part of the stored value or the link 404s. Venmo follows the same
 * rule for consistency, and `DeepLinkBuilderTest` already fixes `@`-prefixed Venmo handles as riding
 * through verbatim. PayPal and Zelle have no sigil.
 *
 * Nothing here throws or blocks on its own: [checkPaymentHandle] returns a verdict and the caller
 * decides what to do with it. The split between [HandleVerdict.Invalid] and [HandleVerdict.WrongApp]
 * is the whole point of the file. "Invalid" cannot work; "WrongApp" works fine somewhere else, and
 * telling someone their Cash App tag belongs in the Cash App field is more useful than telling them
 * PayPal rejects a dollar sign.
 */

private const val WWW_PREFIX = "www."

/** North American numbering plan: 3-digit area code, then a 7-digit subscriber number. */
private const val AREA_CODE_LENGTH = 3
private const val US_DIGITS = 10
private const val US_DIGITS_WITH_COUNTRY_CODE = 11

/** Shortest real top-level domain, so "alex@rivera.c" is not read as an address. */
private const val MIN_TLD_LENGTH = 2

/** The lowest digit an area code or exchange may start with. 0 and 1 are reserved. */
private const val LOWEST_NANP_LEAD_DIGIT = '2'

private const val VENMO_MIN = 5
private const val VENMO_MAX = 30
private const val CASHTAG_MAX = 20
private const val PAYPAL_MAX = 20

/** Prefixes that can only have come from someone pasting a profile URL. Longest match wins. */
private val HOST_PREFIXES =
    listOf(
        "account.venmo.com/u/",
        "paypal.com/paypalme/",
        "venmo.com/u/",
        "paypal.me/",
        "cash.app/",
        "venmo.com/",
        "cash.me/",
    )

/** Toll-free area codes. Zelle's own FAQ rules these out at enrollment. */
private val TOLL_FREE_AREA_CODES = setOf("800", "833", "844", "855", "866", "877", "888")

/** The characters a human might type into a phone number without meaning anything by them. */
private const val PHONE_PUNCTUATION = " ()-.+"

/**
 * Whether the user is still mid-keystroke or has committed. The distinction exists so an incomplete
 * value never flashes red: a 2-character Venmo name is on its way to being a 7-character one, and
 * interrupting at every keystroke is how validation earns its reputation.
 */
enum class HandleStage {
    /** Field has focus. Only report problems that more typing cannot fix. */
    TYPING,

    /** Blur, Continue, or Save. Report everything. */
    SETTLED,
}

/** The outcome of [checkPaymentHandle]. */
sealed interface HandleVerdict {
    /** Nothing entered. Not a problem: every handle is optional. */
    data object Empty : HandleVerdict

    /** Valid and complete. */
    data object Ok : HandleVerdict

    /** Not valid yet, but still could be. Show the hint, say nothing. */
    data object Pending : HandleVerdict

    /** Cannot work as entered. [message] names the fix. Blocks save. */
    data class Invalid(
        val message: String,
    ) : HandleVerdict

    /**
     * Well-formed, but for [belongsTo] rather than the field it was entered in. Never blocks: the user
     * may know something we do not. [canonical] is the value ready to drop into [belongsTo]'s field.
     */
    data class WrongApp(
        val belongsTo: PaymentApp,
        val message: String,
        val canonical: String,
    ) : HandleVerdict
}

/** The service's own sigil, stored as part of the handle. */
fun paymentHandleSigil(app: PaymentApp): String =
    when (app) {
        PaymentApp.VENMO -> "@"
        PaymentApp.CASH_APP -> "$"
        PaymentApp.PAYPAL, PaymentApp.ZELLE -> ""
    }

/** What is printed ahead of the input so the shape of the answer is visible before typing starts. */
fun paymentHandlePrefix(app: PaymentApp): String =
    when (app) {
        PaymentApp.VENMO -> "@"
        PaymentApp.CASH_APP -> "$"
        PaymentApp.PAYPAL -> "paypal.me/"
        PaymentApp.ZELLE -> ""
    }

/** Where to find the handle, with a worked example. Prevents more mistakes than any error corrects. */
fun paymentHandleExample(app: PaymentApp): String =
    when (app) {
        PaymentApp.VENMO -> "In the Venmo app under Me. Looks like @alex-rivera."
        PaymentApp.CASH_APP -> "On your Cash App home screen. Looks like \$alexr."
        PaymentApp.PAYPAL -> "In PayPal under PayPal.Me. Looks like paypal.me/alexrivera."
        PaymentApp.ZELLE -> "The email or US mobile number you enrolled with your bank."
    }

/** The service's display name, for messages that have to name it. */
fun paymentAppName(app: PaymentApp): String =
    when (app) {
        PaymentApp.VENMO -> "Venmo"
        PaymentApp.CASH_APP -> "Cash App"
        PaymentApp.PAYPAL -> "PayPal"
        PaymentApp.ZELLE -> "Zelle"
    }

/**
 * Strips everything we can fix without saying anything: the scheme, `www.`, a known host prefix, a
 * query string, a fragment, and surrounding whitespace.
 *
 * Most handles that fail validation are a pasted profile URL, and a message the user has to stop and
 * read is a worse outcome than a value that was quietly cleaned.
 */
fun stripHandleNoise(raw: String): String {
    var s = raw.trim()
    for (scheme in listOf("https://", "http://")) {
        if (s.startsWith(scheme, ignoreCase = true)) {
            s = s.drop(scheme.length)
            break
        }
    }
    if (s.startsWith(WWW_PREFIX, ignoreCase = true)) s = s.drop(WWW_PREFIX.length)
    for (host in HOST_PREFIXES) {
        if (s.startsWith(host, ignoreCase = true)) {
            s = s.drop(host.length)
            break
        }
    }
    return s
        .substringBefore('?')
        .substringBefore('#')
        .trimEnd('/')
        .trim()
}

/**
 * Canonical stored form for [app] from anything the user might have entered: noise stripped, own sigil
 * normalised to exactly one. A blank or sigil-only value canonicalises to "" so it clears the handle.
 */
fun canonicalPaymentHandle(
    app: PaymentApp,
    raw: String,
): String {
    val stripped = stripHandleNoise(raw)
    val sigil = paymentHandleSigil(app)
    val bare = if (sigil.isEmpty()) stripped else stripped.trimStart(sigil[0])
    return if (bare.isEmpty()) "" else sigil + bare
}

/** The canonical value minus its sigil, which is what the input field holds while the prefix is drawn separately. */
fun paymentHandleBody(
    app: PaymentApp,
    canonical: String,
): String {
    val sigil = paymentHandleSigil(app)
    return if (sigil.isEmpty()) canonical else canonical.trimStart(sigil[0])
}

/**
 * Which service a value's fingerprint points at, or null when nothing identifies it.
 *
 * Drives both the misroute warning and paste routing, so the two can never disagree about what a
 * value is. **Email is tested before the `@`-means-Venmo rule** — in the Zelle field an `@` is an
 * email address, and getting that order wrong would report every Zelle email as a stray Venmo name.
 */
fun detectPaymentApp(raw: String): PaymentApp? {
    val lower = raw.trim().lowercase()
    when {
        lower.contains("venmo.com") -> return PaymentApp.VENMO
        lower.contains("cash.app") || lower.contains("cash.me") -> return PaymentApp.CASH_APP
        lower.contains("paypal.me") || lower.contains("paypal.com") -> return PaymentApp.PAYPAL
    }
    val s = stripHandleNoise(raw)
    return when {
        s.isEmpty() -> null
        looksLikeEmail(s) -> PaymentApp.ZELLE
        looksLikePhoneNumber(s) -> PaymentApp.ZELLE
        s.startsWith('$') -> PaymentApp.CASH_APP
        s.startsWith('@') -> PaymentApp.VENMO
        else -> null
    }
}

/**
 * Checks [raw] as a handle for [app]. [stage] decides whether an incomplete value is reported as
 * [HandleVerdict.Invalid] or held back as [HandleVerdict.Pending].
 */
fun checkPaymentHandle(
    app: PaymentApp,
    raw: String,
    stage: HandleStage,
): HandleVerdict {
    val stripped = stripHandleNoise(raw)
    if (stripped.isEmpty()) return HandleVerdict.Empty

    val sigil = paymentHandleSigil(app)
    val body = if (sigil.isEmpty()) stripped else stripped.trimStart(sigil[0])
    if (body.isEmpty()) return HandleVerdict.Empty

    // Naming the field a value belongs in beats reporting the format error it causes here.
    //
    // Detection runs twice because the field's own sigil can hide a foreign one underneath it: the UI
    // canonicalises "$alexr" typed into the Venmo field to "@$alexr", where the leading "@" is ours and
    // the "$" is the evidence. Testing [raw] alone would see only our own sigil and report a generic
    // illegal-character error instead of naming Cash App.
    val detected =
        detectPaymentApp(raw).takeIf { it != app }
            ?: detectPaymentApp(body).takeIf { it != app }
    if (detected != null) {
        return HandleVerdict.WrongApp(
            belongsTo = detected,
            message = misrouteMessage(detected),
            canonical = canonicalPaymentHandle(detected, body),
        )
    }

    return when (app) {
        PaymentApp.VENMO -> checkVenmo(body, stage)
        PaymentApp.CASH_APP -> checkCashApp(body, stage)
        PaymentApp.PAYPAL -> checkPayPal(body)
        PaymentApp.ZELLE -> checkZelle(stripped, stage)
    }
}

/**
 * Which app should be the preferred one after an edit: [current] if it still has a handle, otherwise
 * the earliest-added one that does, or null when there are none left.
 *
 * Somebody always has to be first when a friend opens the settle screen, so the preference is never
 * left unset while a handle exists. It is only ever *re-derived* when the current pick loses its
 * handle, which keeps a deliberate choice from being overwritten the moment another app is filled in.
 *
 * "Earliest-added" is [handles]' own iteration order. `Map.plus` returns a `LinkedHashMap` and leaves an
 * existing key where it was, so the map the editor builds up is already in the order the user filled
 * the fields in. A handle that is cleared and later refilled keeps its original place rather than going
 * to the back, which is the right answer: it was still the one they added first.
 */
fun resolvePreferredPaymentApp(
    handles: Map<PaymentApp, String>,
    current: PaymentApp?,
): PaymentApp? =
    current?.takeIf { handles[it]?.isNotBlank() == true }
        ?: handles.entries.firstOrNull { it.value.isNotBlank() }?.key

/** True when every handle in [handles] is either blank or passes a settled check. */
fun paymentHandlesAreSaveable(handles: Map<PaymentApp, String>): Boolean =
    handles.all { (app, value) ->
        checkPaymentHandle(app, value, HandleStage.SETTLED) !is HandleVerdict.Invalid
    }

private fun misrouteMessage(belongsTo: PaymentApp): String =
    when (belongsTo) {
        PaymentApp.VENMO -> "That looks like a Venmo username."
        PaymentApp.CASH_APP -> "That looks like a Cash App \$Cashtag."
        PaymentApp.PAYPAL -> "That looks like a PayPal link."
        PaymentApp.ZELLE -> "Zelle is the one that uses an email or phone number."
    }

private fun checkVenmo(
    body: String,
    stage: HandleStage,
): HandleVerdict {
    if (body.any { !it.isAsciiAlnum() && it != '_' && it != '-' }) {
        return HandleVerdict.Invalid("Venmo names use letters, numbers, - and _ only.")
    }
    if (body.length > VENMO_MAX) {
        return HandleVerdict.Invalid("Venmo names are $VENMO_MAX characters at most. That one is ${body.length}.")
    }
    if (body.length < VENMO_MIN) {
        return if (stage == HandleStage.TYPING) {
            HandleVerdict.Pending
        } else {
            HandleVerdict.Invalid("Venmo names are at least $VENMO_MIN characters.")
        }
    }
    return HandleVerdict.Ok
}

private fun checkCashApp(
    body: String,
    stage: HandleStage,
): HandleVerdict {
    if (body.any { !it.isAsciiAlnum() }) {
        val hint = if (body.any { it == '-' || it == '_' }) " Handles with - or _ are usually Venmo." else ""
        return HandleVerdict.Invalid("A \$Cashtag is letters and numbers only.$hint")
    }
    if (body.length > CASHTAG_MAX) {
        return HandleVerdict.Invalid("A \$Cashtag is $CASHTAG_MAX characters at most. That one is ${body.length}.")
    }
    if (body.none { it.isAsciiLetter() }) {
        return if (stage == HandleStage.TYPING) {
            HandleVerdict.Pending
        } else {
            HandleVerdict.Invalid("A \$Cashtag needs at least one letter.")
        }
    }
    return HandleVerdict.Ok
}

private fun checkPayPal(body: String): HandleVerdict {
    if (body.any { !it.isAsciiAlnum() }) {
        val hint = if (body.any { it == '-' || it == '_' }) " Handles with - or _ are usually Venmo." else ""
        return HandleVerdict.Invalid("PayPal links are letters and numbers only.$hint")
    }
    if (body.length > PAYPAL_MAX) {
        return HandleVerdict.Invalid("PayPal links are $PAYPAL_MAX characters at most. That one is ${body.length}.")
    }
    return HandleVerdict.Ok
}

private fun checkZelle(
    value: String,
    stage: HandleStage,
): HandleVerdict {
    if (looksLikeEmail(value)) return HandleVerdict.Ok
    val digits = usMobileDigits(value)
    if (digits != null) {
        val area = digits.take(AREA_CODE_LENGTH)
        if (area in TOLL_FREE_AREA_CODES) {
            return HandleVerdict.Invalid("Toll-free numbers can't enroll with Zelle.")
        }
        if (area[0] < LOWEST_NANP_LEAD_DIGIT || digits[AREA_CODE_LENGTH] < LOWEST_NANP_LEAD_DIGIT) {
            return HandleVerdict.Invalid("That isn't a US mobile number Zelle can use.")
        }
        return HandleVerdict.Ok
    }
    // A half-typed email or phone is neither yet, and there is no shorter shape to hold it to.
    return if (stage == HandleStage.TYPING) {
        HandleVerdict.Pending
    } else {
        HandleVerdict.Invalid("Zelle uses the email or US mobile you enrolled with your bank.")
    }
}

/**
 * A permissive shape test, not RFC 5322: one `@`, something before it, and a dotted domain ending in
 * letters. Anything stricter rejects real addresses, and the only authority on whether an address is
 * enrolled with Zelle is Zelle.
 */
private fun looksLikeEmail(value: String): Boolean {
    if (value.any { it.isWhitespace() }) return false
    val at = value.indexOf('@')
    if (at <= 0 || at != value.lastIndexOf('@')) return false
    val domain = value.substring(at + 1)
    val dot = domain.lastIndexOf('.')
    if (dot <= 0 || dot == domain.lastIndex) return false
    val tld = domain.substring(dot + 1)
    return tld.length >= MIN_TLD_LENGTH && tld.all { it.isAsciiLetter() }
}

/**
 * Stricter than [usMobileDigits], and only used for *detection*.
 *
 * A bare `4155550132` is a perfectly legal Venmo username as well as a phone number, so routing it to
 * Zelle on sight would nag someone whose handle happens to be digits. A number a human typed as a
 * number carries evidence: punctuation, or the leading country code.
 */
private fun looksLikePhoneNumber(value: String): Boolean {
    if (usMobileDigits(value) == null) return false
    return value.any { it in PHONE_PUNCTUATION } ||
        value.count { it.isDigit() } == US_DIGITS_WITH_COUNTRY_CODE
}

/**
 * The ten significant digits of [value] read as a US number, or null when it is not one. Accepts a
 * leading country code 1 and the punctuation people actually type.
 */
private fun usMobileDigits(value: String): String? {
    if (value.isEmpty()) return null
    if (value.any { !it.isDigit() && it !in PHONE_PUNCTUATION }) return null
    val digits = value.filter { it.isDigit() }
    return when {
        digits.length == US_DIGITS -> digits
        digits.length == US_DIGITS_WITH_COUNTRY_CODE && digits.startsWith("1") -> digits.drop(1)
        else -> null
    }
}

// Kotlin's isLetterOrDigit() is Unicode-aware, and every rule above is an ASCII rule. Without this
// distinction "andré" and full-width digits pass a check that the payment app will reject.
private fun Char.isAsciiAlnum(): Boolean = isAsciiLetter() || this in '0'..'9'

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
