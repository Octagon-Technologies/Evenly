package da.chelimo.sharecost.platform

/**
 * E-1 — small-secret store (06 §5.2): session / refresh tokens and the like. Values are encrypted
 * at rest by the platform secure element — **Android Keystore-wrapped AES/GCM** (ciphertext kept in a
 * private `SharedPreferences`), **iOS Keychain** (`kSecClassGenericPassword`). This is the *only*
 * sanctioned home for tokens; they are **never** written to plain SharedPreferences / `UserDefaults` /
 * DataStore (07 §2.1). Use it for small secrets only — not bulk data.
 *
 * Every operation is `suspend` and hops off the main thread: Keystore key generation and Keychain
 * calls touch a secure element / disk, and the project's no-blocking-IO rule (the same one that put
 * non-secret prefs on DataStore, 06 §2.2) forbids doing that on the UI thread.
 *
 * No constructor is declared here on purpose: the actuals need different platform handles (Android a
 * `Context`, iOS only a service name), and instances are always supplied by `platformModule()` — common
 * code injects the bound singleton, it never constructs one.
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
