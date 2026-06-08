package da.chelimo.sharecost.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * iOS builder for [ShareCostDatabase]. No Context exists on iOS; the file lives in the app's
 * sandbox Documents directory, resolved through Foundation. The bundled SQLite driver (set in the
 * common [getRoomDatabase]) means iOS does not depend on the system SQLite version.
 */
@OptIn(ExperimentalForeignApi::class)
fun shareCostDatabaseBuilder(): RoomDatabase.Builder<ShareCostDatabase> {
    val documentsUrl = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    val dbPath = requireNotNull(documentsUrl?.path) { "Could not resolve iOS Documents directory" }
    return Room.databaseBuilder<ShareCostDatabase>(name = "$dbPath/$SHARECOST_DB_FILE")
}
