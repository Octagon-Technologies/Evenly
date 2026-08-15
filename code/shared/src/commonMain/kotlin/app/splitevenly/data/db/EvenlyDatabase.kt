package app.splitevenly.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.splitevenly.data.db.dao.BillParticipantDao
import app.splitevenly.data.db.dao.CategoryDao
import app.splitevenly.data.db.dao.CommentDao
import app.splitevenly.data.db.dao.ConflictDao
import app.splitevenly.data.db.dao.ExpenseBlockedUserDao
import app.splitevenly.data.db.dao.ExpenseDao
import app.splitevenly.data.db.dao.ExpenseEditConflictDao
import app.splitevenly.data.db.dao.ExpenseItemDao
import app.splitevenly.data.db.dao.ExpenseSyncStateDao
import app.splitevenly.data.db.dao.FxCurrencyDao
import app.splitevenly.data.db.dao.FxRateDao
import app.splitevenly.data.db.dao.GroupDao
import app.splitevenly.data.db.dao.GroupPassDao
import app.splitevenly.data.db.dao.GroupScanUsageDao
import app.splitevenly.data.db.dao.HistoryEventDao
import app.splitevenly.data.db.dao.ItemClaimDao
import app.splitevenly.data.db.dao.ItemShareDao
import app.splitevenly.data.db.dao.MemberDao
import app.splitevenly.data.db.dao.PendingItemEditDao
import app.splitevenly.data.db.dao.PlaceholderClaimAnswerDao
import app.splitevenly.data.db.dao.PlaceholderMergeDao
import app.splitevenly.data.db.dao.ReceiptDao
import app.splitevenly.data.db.dao.ReceiptUploadDao
import app.splitevenly.data.db.dao.RowSyncStateDao
import app.splitevenly.data.db.dao.SettlementDao
import app.splitevenly.data.db.dao.ShareDao
import app.splitevenly.data.db.dao.SignOutWipeDao
import app.splitevenly.data.db.dao.SupersededNoticeDao
import app.splitevenly.data.db.dao.UserDao
import app.splitevenly.data.db.dao.UserSubscriptionDao
import app.splitevenly.data.db.entity.BillParticipantEntity
import app.splitevenly.data.db.entity.CategoryEntity
import app.splitevenly.data.db.entity.CommentEntity
import app.splitevenly.data.db.entity.ConflictEntity
import app.splitevenly.data.db.entity.ExpenseBlockedUserEntity
import app.splitevenly.data.db.entity.ExpenseEditConflictEntity
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.ExpenseItemEntity
import app.splitevenly.data.db.entity.ExpenseSyncStateEntity
import app.splitevenly.data.db.entity.FxBakedEntity
import app.splitevenly.data.db.entity.FxCurrencyEntity
import app.splitevenly.data.db.entity.FxRateEntity
import app.splitevenly.data.db.entity.GroupEntity
import app.splitevenly.data.db.entity.GroupPassEntity
import app.splitevenly.data.db.entity.GroupScanUsageEntity
import app.splitevenly.data.db.entity.HistoryEventEntity
import app.splitevenly.data.db.entity.ItemClaimEntity
import app.splitevenly.data.db.entity.ItemShareEntity
import app.splitevenly.data.db.entity.MemberEntity
import app.splitevenly.data.db.entity.PendingItemEditEntity
import app.splitevenly.data.db.entity.PlaceholderClaimAnswerEntity
import app.splitevenly.data.db.entity.ReceiptEntity
import app.splitevenly.data.db.entity.ReceiptUploadEntity
import app.splitevenly.data.db.entity.RowSyncStateEntity
import app.splitevenly.data.db.entity.SettlementAllocationEntity
import app.splitevenly.data.db.entity.SettlementEntity
import app.splitevenly.data.db.entity.ShareEntity
import app.splitevenly.data.db.entity.SupersededNoticeEntity
import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.db.entity.UserSubscriptionEntity
import kotlinx.coroutines.Dispatchers

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
        PlaceholderClaimAnswerEntity::class,
        ExpenseEntity::class,
        ShareEntity::class,
        SettlementEntity::class,
        SettlementAllocationEntity::class,
        FxRateEntity::class,
        FxBakedEntity::class,
        FxCurrencyEntity::class,
        ConflictEntity::class,
        ExpenseEditConflictEntity::class,
        ExpenseSyncStateEntity::class,
        CommentEntity::class,
        ExpenseBlockedUserEntity::class,
        ReceiptEntity::class,
        ReceiptUploadEntity::class,
        HistoryEventEntity::class,
        CategoryEntity::class,
        ExpenseItemEntity::class,
        ItemClaimEntity::class,
        ItemShareEntity::class,
        BillParticipantEntity::class,
        PendingItemEditEntity::class,
        GroupPassEntity::class,
        UserSubscriptionEntity::class,
        GroupScanUsageEntity::class,
        RowSyncStateEntity::class,
        SupersededNoticeEntity::class,
    ],
    version = 28,
    exportSchema = true,
)
@ConstructedBy(EvenlyDatabaseConstructor::class)
abstract class EvenlyDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao

    abstract fun groupDao(): GroupDao

    abstract fun memberDao(): MemberDao

    abstract fun placeholderMergeDao(): PlaceholderMergeDao

    abstract fun placeholderClaimAnswerDao(): PlaceholderClaimAnswerDao

    abstract fun expenseDao(): ExpenseDao

    abstract fun shareDao(): ShareDao

    abstract fun settlementDao(): SettlementDao

    abstract fun fxRateDao(): FxRateDao

    abstract fun fxCurrencyDao(): FxCurrencyDao

    abstract fun conflictDao(): ConflictDao

    abstract fun expenseEditConflictDao(): ExpenseEditConflictDao

    abstract fun expenseSyncStateDao(): ExpenseSyncStateDao

    abstract fun commentDao(): CommentDao

    abstract fun expenseBlockedUserDao(): ExpenseBlockedUserDao

    abstract fun receiptDao(): ReceiptDao

    abstract fun receiptUploadDao(): ReceiptUploadDao

    abstract fun historyEventDao(): HistoryEventDao

    abstract fun categoryDao(): CategoryDao

    abstract fun expenseItemDao(): ExpenseItemDao

    abstract fun itemClaimDao(): ItemClaimDao

    abstract fun itemShareDao(): ItemShareDao

    abstract fun billParticipantDao(): BillParticipantDao

    abstract fun pendingItemEditDao(): PendingItemEditDao

    abstract fun groupPassDao(): GroupPassDao

    abstract fun userSubscriptionDao(): UserSubscriptionDao

    abstract fun groupScanUsageDao(): GroupScanUsageDao

    abstract fun rowSyncStateDao(): RowSyncStateDao

    abstract fun supersededNoticeDao(): SupersededNoticeDao

    /** Sign-out cache wipe. Adding an entity above means adding a line in there too — see its KDoc. */
    abstract fun signOutWipeDao(): SignOutWipeDao
}

/**
 * KSP generates the `actual` of this object per target. The compiler can't see the generated code
 * during analysis, hence the suppress — this is the standard Room-KMP incantation, not a code smell.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object EvenlyDatabaseConstructor : RoomDatabaseConstructor<EvenlyDatabase> {
    override fun initialize(): EvenlyDatabase
}

/**
 * Finishes a platform-supplied [RoomDatabase.Builder] into a usable DB. The bundled SQLite driver
 * ships its own SQLite, so behaviour is identical on Android and iOS (no reliance on the OS's
 * system SQLite version). Platform code supplies the builder (file path differs per OS); this
 * common step pins the driver so there is exactly one place that decision lives.
 */
fun getRoomDatabase(builder: RoomDatabase.Builder<EvenlyDatabase>): EvenlyDatabase =
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
const val EVENLY_DB_FILE: String = "evenly.db"
