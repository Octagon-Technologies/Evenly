package app.splitevenly.core.log

import co.touchlab.kermit.Logger

/**
 * App-owned logging facade over Kermit (06 §9.1) so call sites never import a vendor type.
 * Sensitive data (tokens, emails, amounts, names, handles) MUST NOT be logged (07 §4).
 */
object Log {
    private val logger = Logger.withTag("Evenly")

    fun d(message: String, throwable: Throwable? = null) = logger.d(throwable) { message }
    fun i(message: String, throwable: Throwable? = null) = logger.i(throwable) { message }
    fun w(message: String, throwable: Throwable? = null) = logger.w(throwable) { message }
    fun e(message: String, throwable: Throwable? = null) = logger.e(throwable) { message }
}
