package app.splitevenly.platform

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Android [ReceiptFileStore] over the app's internal `filesDir/receipt_uploads/`. */
actual class ReceiptFileStore(context: Context) {

    private val dir: File = File(context.filesDir, DIR).apply { mkdirs() }

    actual suspend fun save(id: String, ext: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val file = File(dir, "$id.$ext")
            file.writeBytes(bytes)
            file.absolutePath
        }

    actual suspend fun read(path: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            if (file.exists()) file.readBytes() else null
        }

    actual fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    private companion object {
        const val DIR = "receipt_uploads"
    }
}
