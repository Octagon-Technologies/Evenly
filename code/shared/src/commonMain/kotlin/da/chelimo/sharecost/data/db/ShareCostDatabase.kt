package da.chelimo.sharecost.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import da.chelimo.sharecost.data.db.dao.BillParticipantDao
import da.chelimo.sharecost.data.db.dao.CategoryDao
import da.chelimo.sharecost.data.db.dao.CommentDao
import da.chelimo.sharecost.data.db.dao.ConflictDao
import da.chelimo.sharecost.data.db.dao.ExpenseDao
import da.chelimo.sharecost.data.db.dao.ExpenseEditConflictDao
import da.chelimo.sharecost.data.db.dao.ExpenseItemDao
import da.chelimo.sharecost.data.db.dao.ExpenseSyncStateDao
import da.chelimo.sharecost.data.db.dao.FxRateDao
import da.chelimo.sharecost.data.db.dao.GroupDao
import da.chelimo.sharecost.data.db.dao.HistoryEventDao
import da.chelimo.sharecost.data.db.dao.ItemClaimDao
import da.chelimo.sharecost.data.db.dao.ItemShareDao
import da.chelimo.sharecost.data.db.dao.MemberDao
import da.chelimo.sharecost.data.db.dao.ReceiptDao
import da.chelimo.sharecost.data.db.dao.ReceiptUploadDao
import da.chelimo.sharecost.data.db.dao.SettlementDao
import da.chelimo.sharecost.data.db.dao.ShareDao
import da.chelimo.sharecost.data.db.dao.UserDao
import da.chelimo.sharecost.data.db.entity.BillParticipantEntity
import da.chelimo.sharecost.data.db.entity.CategoryEntity
import da.chelimo.sharecost.data.db.entity.CommentEntity
import da.chelimo.sharecost.data.db.entity.ConflictEntity
import da.chelimo.sharecost.data.db.entity.ExpenseEditConflictEntity
import da.chelimo.sharecost.data.db.entity.ExpenseEntity
import da.chelimo.sharecost.data.db.entity.ExpenseItemEntity
import da.chelimo.sharecost.data.db.entity.ExpenseSyncStateEntity
import da.chelimo.sharecost.data.db.entity.FxBakedEntity
import da.chelimo.sharecost.data.db.entity.FxRateEntity
import da.chelimo.sharecost.data.db.entity.GroupEntity
import da.chelimo.sharecost.data.db.entity.HistoryEventEntity
import da.chelimo.sharecost.data.db.entity.ItemClaimEntity
import da.chelimo.sharecost.data.db.entity.ItemShareEntity
import da.chelimo.sharecost.data.db.entity.MemberEntity
import da.chelimo.sharecost.data.db.entity.ReceiptEntity
import da.chelimo.sharecost.data.db.entity.ReceiptUploadEntity
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
        ConflictEntity::class,
        ExpenseEditConflictEntity::class,
        ExpenseSyncStateEntity::class,
        CommentEntity::class,
        ReceiptEntity::class,
        ReceiptUploadEntity::class,
        HistoryEventEntity::class,
        CategoryEntity::class,
        ExpenseItemEntity::class,
        ItemClaimEntity::class,
        ItemShareEntity::class,
        BillParticipantEntity::class,
    ],
    version = 14,
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
    abstract fun conflictDao(): ConflictDao
    abstract fun expenseEditConflictDao(): ExpenseEditConflictDao
    abstract fun expenseSyncStateDao(): ExpenseSyncStateDao
    abstract fun commentDao(): CommentDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun receiptUploadDao(): ReceiptUploadDao
    abstract fun historyEventDao(): HistoryEventDao
    abstract fun categoryDao(): CategoryDao
    abstract fun expenseItemDao(): ExpenseItemDao
    abstract fun itemClaimDao(): ItemClaimDao
    abstract fun itemShareDao(): ItemShareDao
    abstract fun billParticipantDao(): BillParticipantDao
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
        // Pre-release: the local cache is fully rebuildable from the server, so a schema bump just
        // drops and recreates rather than carrying hand-written migrations. Revisit before GA / S-1.
        .fallbackToDestructiveMigration(dropAllTables = true)
        // Run suspend queries off the caller's thread so a DB read never blocks the UI. We use
        // Dispatchers.Default because Dispatchers.IO isn't declared in commonMain; a platform
        // builder may override this with IO for genuinely blocking file I/O.
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()

/** Local DB file name, shared by the Android and iOS builders. */
const val SHARECOST_DB_FILE: String = "sharecost.db"
