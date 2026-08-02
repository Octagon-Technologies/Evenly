package app.splitevenly.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Android builder for [EvenlyDatabase]. Needs a [Context] (Android resolves the DB file through
 * the app's private storage dir), which is why this can't live in commonMain — it's the classic
 * reason a Room DB is created via platform code and handed to a common [getRoomDatabase].
 *
 * We use `applicationContext` so the builder never captures an Activity (that would leak it).
 */
fun evenlyDatabaseBuilder(context: Context): RoomDatabase.Builder<EvenlyDatabase> {
    val dbFile = context.applicationContext.getDatabasePath(EVENLY_DB_FILE)
    return Room.databaseBuilder<EvenlyDatabase>(
        context = context.applicationContext,
        name = dbFile.absolutePath,
    )
}
