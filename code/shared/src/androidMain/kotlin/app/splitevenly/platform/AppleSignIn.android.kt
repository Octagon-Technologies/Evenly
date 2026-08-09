package app.splitevenly.platform

import app.splitevenly.core.error.AppError
import app.splitevenly.core.error.AppResult

/**
 * Android has no native "Sign In with Apple" API. Callers gate on [isIOS] before reaching for
 * [AppleSignIn], so this path never actually runs; it exists only to satisfy the `expect`/`actual`
 * contract (see `platform/AGENTS.md`).
 */
actual class AppleSignIn {
    actual suspend fun signIn(): AppResult<AppleSignInResult> =
        AppResult.Err(AppError.Unexpected(UnsupportedOperationException("Native Apple Sign In is iOS-only")))
}
