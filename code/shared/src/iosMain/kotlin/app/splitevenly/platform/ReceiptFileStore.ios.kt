@file:OptIn(ExperimentalForeignApi::class)

package app.splitevenly.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS [ReceiptFileStore] over `<Documents>/receipt_uploads/`. The directory sits in Documents (not a
 * cache dir) so the OS never reclaims a file with an upload still pending; the uploader deletes each
 * file once its bytes land in Storage.
 */
actual class ReceiptFileStore {

    private val dir: String by lazy {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: "."
        val path = "$docs/receipt_uploads"
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        path
    }

    actual suspend fun save(id: String, ext: String, bytes: ByteArray): String =
        withContext(Dispatchers.Default) {
            val path = "$dir/$id.$ext"
            bytes.toNSData().writeToFile(path, true)
            path
        }

    actual suspend fun read(path: String): ByteArray? =
        withContext(Dispatchers.Default) {
            (NSData.dataWithContentsOfFile(path))?.toByteArray()
        }

    actual fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}
