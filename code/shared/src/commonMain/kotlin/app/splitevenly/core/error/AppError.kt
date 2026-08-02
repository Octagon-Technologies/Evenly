package app.splitevenly.core.error

/**
 * Typed, exhaustive error model (06 §9.2). Errors are VALUES, not thrown exceptions, once they
 * cross out of the `data` layer. Each variant carries exactly what the UI needs to render a
 * message and decide recoverability (retry vs. re-login vs. field highlight).
 */
sealed interface AppError {

    /** Connectivity / transport failure — recoverable by retry. */
    data class Network(val kind: Kind, val cause: Throwable? = null) : AppError {
        enum class Kind { Offline, Timeout, Tls, Unreachable }
    }

    /** Backend returned an error (HTTP 4xx/5xx, PostgREST/Postgres). `code` enables i18n mapping. */
    data class Backend(val status: Int?, val code: String?, val detail: String?) : AppError

    /** Local, pre-flight validation — drives per-field UI highlighting. */
    data class Validation(val fieldErrors: Map<String, Reason>) : AppError {
        enum class Reason { Required, TooShort, TooLong, OutOfRange, Malformed, Duplicate }
    }

    /** Optimistic-concurrency / sync conflict (02 §sync). UI offers refresh / merge. */
    data class Conflict(val entity: String, val serverVersion: Long? = null) : AppError

    /** Auth / session — UI routes to (re-)login. */
    data object SessionExpired : AppError

    /** Authenticated but not permitted for this action. */
    data object NotAuthorized : AppError

    /** Truly unexpected — log + generic message; never silently swallowed. */
    data class Unexpected(val cause: Throwable) : AppError
}
