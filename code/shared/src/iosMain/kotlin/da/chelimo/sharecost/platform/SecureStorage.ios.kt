@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package da.chelimo.sharecost.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import da.chelimo.sharecost.core.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * iOS [SecureStorage] (06 §5.2) over the **Keychain** (`kSecClassGenericPassword`). Items are scoped to
 * [service] and made `AfterFirstUnlockThisDeviceOnly` — readable in the background after the first unlock
 * but never migrated to a new device or iCloud Keychain backup, which is the right posture for an auth
 * token. The fiddly `Security.framework` C API is wrapped behind small helpers ([keychainOp], [cfDictionaryOf])
 * that keep every `CFBridgingRetain` balanced by a `CFBridgingRelease`.
 *
 * Failures **degrade rather than crash**: an unexpected `OSStatus` (e.g. the Keychain being unavailable to
 * a sandbox without entitlements, `errSecNotAvailable`) is logged and treated as "no value / write lost".
 * For an auth-token store that means the user is asked to sign in again — a far better outcome than an app
 * crash deep in a secure-storage call.
 */
actual class SecureStorage(service: String = DEFAULT_SERVICE) {

    // Retained once for this (process-lifetime, DI-singleton) instance; CFDictionaryCreate uses null
    // callbacks so it never retains its members, hence the single owning reference here.
    private val cfService: CFTypeRef? = CFBridgingRetain(service as NSString)

    private val defaultProperties: Map<CFStringRef?, CFTypeRef?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to cfService,
    )

    actual suspend fun putString(key: String, value: String): Unit = withContext(Dispatchers.Default) {
        val data = value.encodeToByteArray().toNSData()
        if (!add(key, data)) update(key, data)
    }

    actual suspend fun getString(key: String): String? = withContext(Dispatchers.Default) {
        read(key)?.toByteArray()?.decodeToString()
    }

    actual suspend fun remove(key: String): Unit = withContext(Dispatchers.Default) {
        cfRetain(key) { cfKey ->
            keychainOp(kSecAttrAccount to cfKey) { SecItemDelete(it) }.warnUnless(errSecItemNotFound)
        }
    }

    actual suspend fun contains(key: String): Boolean = withContext(Dispatchers.Default) {
        read(key) != null
    }

    actual suspend fun clear(): Unit = withContext(Dispatchers.Default) {
        memScoped { keychainOp { SecItemDelete(it) }.warnUnless(errSecItemNotFound) }
    }

    // --- Keychain CRUD ---------------------------------------------------------------------------

    /** @return `true` if the item was added (or the write failed unrecoverably); `false` only if it already
     * exists, in which case the caller routes to [update]. */
    private fun add(key: String, value: NSData?): Boolean = cfRetain(key, value) { cfKey, cfValue ->
        val status = keychainOp(
            kSecAttrAccount to cfKey,
            kSecValueData to cfValue,
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ) { SecItemAdd(it, null) }
        when (status) {
            errSecDuplicateItem -> false // exists → update() in place
            else -> { status.warnUnless(); true } // added, or a logged failure we won't try to update over
        }
    }

    private fun update(key: String, value: NSData?): Unit = cfRetain(key, value) { cfKey, cfValue ->
        keychainOp(kSecAttrAccount to cfKey) { query ->
            val attributes = cfDictionaryOf(mapOf(kSecValueData to cfValue))
            val status = SecItemUpdate(query, attributes)
            CFBridgingRelease(attributes)
            status
        }.warnUnless()
    }

    private fun read(key: String): NSData? = cfRetain(key) { cfKey ->
        val out = alloc<CFTypeRefVar>()
        val status = keychainOp(
            kSecAttrAccount to cfKey,
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        ) { SecItemCopyMatching(it, out.ptr) }
        when (status) {
            errSecSuccess -> CFBridgingRelease(out.value) as? NSData
            errSecItemNotFound -> null
            else -> { status.warnUnless(); null }
        }
    }

    // --- CoreFoundation plumbing ------------------------------------------------------------------

    /** Run a Keychain operation whose query is the default class/service properties plus [extra]. */
    private inline fun MemScope.keychainOp(
        vararg extra: Pair<CFStringRef?, CFTypeRef?>,
        operation: (query: CFDictionaryRef?) -> OSStatus,
    ): OSStatus {
        val query = cfDictionaryOf(defaultProperties + mapOf(*extra))
        val status = operation(query)
        CFBridgingRelease(query)
        return status
    }

    private fun MemScope.cfDictionaryOf(map: Map<CFStringRef?, CFTypeRef?>): CFDictionaryRef? {
        val keys = allocArrayOf(*map.keys.toTypedArray())
        val values = allocArrayOf(*map.values.toTypedArray())
        return CFDictionaryCreate(
            kCFAllocatorDefault,
            keys.reinterpret(),
            values.reinterpret(),
            map.size.convert(),
            null,
            null,
        )
    }

    /** Log a warning unless [this] status is success (or one of the [allowed] benign codes). Returns whether
     * the status was OK, so call sites can branch. Never throws — secure-storage failures degrade. */
    private fun OSStatus.warnUnless(vararg allowed: OSStatus): Boolean {
        val ok = this == errSecSuccess || this in allowed
        if (!ok) Log.w("SecureStorage keychain operation returned status $this")
        return ok
    }

    /** Bridge one Kotlin/Foundation value into a CF reference for the duration of [block]. */
    private inline fun <T> cfRetain(value: Any?, block: MemScope.(CFTypeRef?) -> T): T = memScoped {
        val cf = CFBridgingRetain(value)
        try {
            block(cf)
        } finally {
            CFBridgingRelease(cf)
        }
    }

    /** Bridge two values; both releases are balanced even if [block] throws. */
    private inline fun <T> cfRetain(a: Any?, b: Any?, block: MemScope.(CFTypeRef?, CFTypeRef?) -> T): T = memScoped {
        val cfA = CFBridgingRetain(a)
        val cfB = CFBridgingRetain(b)
        try {
            block(cfA, cfB)
        } finally {
            CFBridgingRelease(cfA)
            CFBridgingRelease(cfB)
        }
    }

    companion object {
        const val DEFAULT_SERVICE: String = "da.chelimo.sharecost.secure"
    }
}
