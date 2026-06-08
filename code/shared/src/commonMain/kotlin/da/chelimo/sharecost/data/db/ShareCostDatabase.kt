package da.chelimo.sharecost.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import da.chelimo.sharecost.data.db.dao.ExpenseDao
import da.chelimo.sharecost.data.db.dao.FxRateDao
import da.chelimo.sharecost.data.db.dao.GroupDao
import da.chelimo.sharecost.data.db.dao.MemberDao
import da.chelimo.sharecost.data.db.dao.SettlementDao
import da.chelimo.sharecost.data.db.dao.ShareDao
import da.chelimo.sharecost.data.db.dao.UserDao
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.FxBakedEntity
import da.chelimo.sharecost.data.db.entity.FxRateEntity
import da.chelimo.sharecost.data.db.entity.GroupEntity
import da.chelimo.sharecost.data.db.entity.MemberEntity
import da.chelimo.sharecost.data.db.entity.SettlementAllocationEntity
import da.chelimo.sharecost.data.db.entity.SettlementEntity
import da.chelimo.sharecost.data.db.entity.ShareEntity
import da.chelimo.sharecost.data.db.entity.UserEntity

/**
 * Room KMP database (02 §7). The local schema mirrors the Supabase Postgres schema 1:1, with the
 * type adjustments in 02 §7: `timestamptz` → epoch-ms `Long`, `uuid`/`jsonb` → `TEXT`, enums →
 * `TEXT` (validated at the domain boundary, not by Room).
 *
 * Entities are dumb DTOs: only `String`/`Long`/`Boolean` columns, no type converters, no Room
 * foreign keys (see the FK note below). This keeps the table a faithful wire-mirror so the sync
 * layer can read/write rows without going through domain types.
 *
 * **No Room `foreignKeys`.** This is an offline-first synced cache. Rows arrive from the server in
 * dependency-arbitrary order (a `share` can sync before its `expense`). Hard FK constraints would
 * reject the early child insert. We therefore index FK columns for query speed but let the *server*
 * own referential integrity; the local DB only ever holds rows the user is entitled to see.
 */
@Database(
    entities = [
        UserEntity::class,
        GroupEntity::class,
        MemberEntity::class,
        ExpenseEntity::class,
        ShareEntity::class,
        SettlementEntity::class,
        SettlementAllocationEntity::class,
        FxRateEntity::class,
        FxBakedEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(ShareCostDatabaseConstructor::class)
abstract class ShareCostDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun groupDao(): GroupDao
    abstract fun memberDao(): MemberDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun shareDao(): ShareDao
    abstract fun settlementDao(): SettlementDao
    abstract fun fxRateDao(): FxRateDao
}

/**
 * KSP generates the `actual` of this object per target. The compiler can't see the generated code
 * during analysis, hence the suppress — this is the standard Room-KMP incantation, not a code smell.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object ShareCostDatabaseConstructor : RoomDatabaseConstructor<ShareCostDatabase> {
    override fun initialize(): ShareCostDatabase
}

/**
 * Finishes a platform-supplied [RoomDatabase.Builder] into a usable DB. The bundled SQLite driver
 * ships its own SQLite, so behaviour is identical on Android and iOS (no reliance on the OS's
 * system SQLite version). Platform code supplies the builder (file path differs per OS); this
 * common step pins the driver so there is exactly one place that decision lives.
 */
fun getRoomDatabase(builder: RoomDatabase.Builder<ShareCostDatabase>): ShareCostDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .build()

/** Local DB file name, shared by the Android and iOS builders. */
const val SHARECOST_DB_FILE: String = "sharecost.db"
