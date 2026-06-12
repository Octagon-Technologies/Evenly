package da.chelimo.sharecost.platform

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android [SecureStorage] (06 §5.2): an AES-256-GCM key lives in the **`AndroidKeyStore`** (hardware-backed
 * where available, non-exportable), and we keep only the ciphertext in a private `SharedPreferences`. This
 * is exactly the wrapper the now-deprecated `EncryptedSharedPreferences` used to provide — rolled by hand so
 * we don't depend on `androidx.security:security-crypto` (06 §2.2).
 *
 * GCM is authenticated encryption: a tampered or truncated entry fails to decrypt, and [getString] then
 * returns `null` rather than garbage. Each write uses a fresh random IV (never reuse an IV under one GCM key),
 * stored alongside the ciphertext as `base64(iv):base64(ct)`.
 */
actual class SecureStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    actual suspend fun putString(key: String, value: String): Unit = withContext(Dispatchers.Default) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        prefs.edit().putString(key, encode(iv, ciphertext)).apply()
    }

    actual suspend fun getString(key: String): String? = withContext(Dispatchers.Default) {
        val stored = prefs.getString(key, null) ?: return@withContext null
        runCatching {
            val (iv, ciphertext) = decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            cipher.doFinal(ciphertext).decodeToString()
        }.getOrNull()
    }

    actual suspend fun remove(key: String): Unit = withContext(Dispatchers.Default) {
        prefs.edit().remove(key).apply()
    }

    actual suspend fun contains(key: String): Boolean = withContext(Dispatchers.Default) {
        prefs.contains(key)
    }

    actual suspend fun clear(): Unit = withContext(Dispatchers.Default) {
        prefs.edit().clear().apply()
    }

    /** Fetch the per-app AES key from the Keystore, generating it on first use. */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(iv: ByteArray, ciphertext: ByteArray): String =
        Base64.encodeToString(iv, Base64.NO_WRAP) + SEPARATOR + Base64.encodeToString(ciphertext, Base64.NO_WRAP)

    private fun decode(stored: String): Pair<ByteArray, ByteArray> {
        val parts = stored.split(SEPARATOR)
        require(parts.size == 2) { "corrupt secure entry" }
        return Base64.decode(parts[0], Base64.NO_WRAP) to Base64.decode(parts[1], Base64.NO_WRAP)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "da.chelimo.sharecost.securestorage"
        const val PREFS_NAME = "sharecost_secure"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
        const val SEPARATOR = ":"
    }
}
