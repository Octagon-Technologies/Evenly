package app.splitevenly.platform

/**
 * E-1 — small-secret store (06 §5.2) for session and refresh tokens. Values are encrypted at rest using
 * platform-protected key material: Android stores AES-GCM ciphertext in private `SharedPreferences` with
 * a non-exportable Android Keystore key; iOS uses Keychain generic-password items. Hardware-backed key
 * protection is used on Android devices that support it, but is not a portability guarantee.
 *
 * This is the only sanctioned home for tokens: never write them to plain `SharedPreferences`,
 * `UserDefaults`, or DataStore (07 §2.1). Store only small secrets, not application data or files.
 * Callers must treat an unreadable value as an expired session: a key can become unavailable after device
 * security changes, app restore, or corrupted storage, and [getString] returns `null` rather than exposing
 * a crypto implementation failure.
 *
 * Every operation is `suspend` and runs off the main thread because keystore and keychain access may touch
 * disk or secure hardware. No constructor is declared here: platform actuals require different handles,
 * and `platformModule()` provides the singleton used by common code.
 */
expect class SecureStorage {
    /** Encrypt and persist [value] under [key], replacing any existing entry. */
    suspend fun putString(key: String, value: String)

    /** Decrypt and return the value for [key], or `null` if absent / unreadable. */
    suspend fun getString(key: String): String?

    /** Remove the entry for [key] (no-op if absent). */
    suspend fun remove(key: String)

    /** `true` if an entry exists for [key]. */
    suspend fun contains(key: String): Boolean

    /** Drop every entry this store owns (e.g. on sign-out). */
    suspend fun clear()
}
