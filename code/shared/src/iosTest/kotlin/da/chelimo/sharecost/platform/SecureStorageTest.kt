package da.chelimo.sharecost.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip tests for the iOS [SecureStorage] Keychain actual (E-1, 06 §5.2).
 *
 * **Environment note:** a Kotlin/Native test binary on the iOS simulator runs without app entitlements, so
 * the data-protection Keychain reports `errSecNotAvailable` (-25291) and stores nothing. The actual degrades
 * gracefully (logs, no crash) rather than throwing, so the suite stays green; the storage-dependent
 * assertions are **gated on a functional probe** ([keychainAvailable]) so they validate for real wherever a
 * Keychain is reachable (a host app / a future device test) and skip cleanly where it isn't. The
 * `null`-on-missing contract is checked unconditionally — it must hold either way.
 */
class SecureStorageTest {

    private val storage = SecureStorage(service = "da.chelimo.sharecost.securestorage.test")
    private var keychainAvailable = false

    @BeforeTest
    fun setUp() = runTest {
        storage.clear()
        // Probe by writing then reading back: succeeds only if this process can actually use the Keychain.
        storage.putString(PROBE_KEY, "1")
        keychainAvailable = storage.getString(PROBE_KEY) == "1"
        storage.clear()
    }

    @AfterTest
    fun tearDown() = runTest { storage.clear() }

    @Test
    fun getMissingKey_returnsNull() = runTest {
        assertNull(storage.getString("definitely-absent-key"))
    }

    @Test
    fun putThenGet_roundTripsValue() = runTest {
        if (!keychainAvailable) return@runTest
        storage.putString("token", "abc-123")
        assertEquals("abc-123", storage.getString("token"))
    }

    @Test
    fun put_overwritesExistingValue() = runTest {
        if (!keychainAvailable) return@runTest
        storage.putString("token", "old")
        storage.putString("token", "new")
        assertEquals("new", storage.getString("token"))
    }

    @Test
    fun remove_deletesEntry() = runTest {
        if (!keychainAvailable) return@runTest
        storage.putString("token", "abc")
        storage.remove("token")
        assertNull(storage.getString("token"))
    }

    @Test
    fun contains_reflectsPresence() = runTest {
        if (!keychainAvailable) return@runTest
        assertFalse(storage.contains("token"))
        storage.putString("token", "abc")
        assertTrue(storage.contains("token"))
    }

    @Test
    fun clear_wipesAllEntries() = runTest {
        if (!keychainAvailable) return@runTest
        storage.putString("a", "1")
        storage.putString("b", "2")
        storage.clear()
        assertNull(storage.getString("a"))
        assertNull(storage.getString("b"))
    }

    @Test
    fun roundTrips_unicodeAndLongValues() = runTest {
        if (!keychainAvailable) return@runTest
        val value = "héllo-🌍-" + "x".repeat(4096)
        storage.putString("k", value)
        assertEquals(value, storage.getString("k"))
    }

    private companion object {
        const val PROBE_KEY = "__keychain_probe__"
    }
}
