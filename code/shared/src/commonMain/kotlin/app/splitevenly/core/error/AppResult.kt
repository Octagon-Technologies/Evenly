package app.splitevenly.core.error

/**
 * Explicit result type (06 §9.2) so error handling is exhaustive — no `Throwable` boxing like
 * `kotlin.Result`. Compose with [map]/[flatMap]; consume with [fold]/[getOrElse].
 */
sealed interface AppResult<out T> {
    data class Ok<out T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Ok -> AppResult.Ok(transform(value))
        is AppResult.Err -> this
    }

inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> =
    when (this) {
        is AppResult.Ok -> transform(value)
        is AppResult.Err -> this
    }

inline fun <T> AppResult<T>.onOk(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Ok) action(value)
    return this
}

inline fun <T> AppResult<T>.onErr(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Err) action(error)
    return this
}

inline fun <T> AppResult<T>.getOrElse(fallback: (AppError) -> @UnsafeVariance T): T =
    when (this) {
        is AppResult.Ok -> value
        is AppResult.Err -> fallback(error)
    }

inline fun <T, R> AppResult<T>.fold(onOk: (T) -> R, onErr: (AppError) -> R): R =
    when (this) {
        is AppResult.Ok -> onOk(value)
        is AppResult.Err -> onErr(error)
    }

fun <T> T.asOk(): AppResult<T> = AppResult.Ok(this)
fun AppError.asErr(): AppResult<Nothing> = AppResult.Err(this)
