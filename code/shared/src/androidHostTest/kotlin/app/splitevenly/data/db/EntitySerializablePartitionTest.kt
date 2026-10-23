package app.splitevenly.data.db

import app.splitevenly.data.db.entity.BillParticipantEntity
import app.splitevenly.data.db.entity.CategoryEntity
import app.splitevenly.data.db.entity.CommentEntity
import app.splitevenly.data.db.entity.ConflictEntity
import app.splitevenly.data.db.entity.ExpenseBlockedUserEntity
import app.splitevenly.data.db.entity.ExpenseEditConflictEntity
import app.splitevenly.data.db.entity.ExpenseEntity
import app.splitevenly.data.db.entity.ExpenseItemEntity
import app.splitevenly.data.db.entity.ExpenseSyncStateEntity
import app.splitevenly.data.db.entity.FeedbackOutboxEntity
import app.splitevenly.data.db.entity.FxBakedEntity
import app.splitevenly.data.db.entity.FxCurrencyEntity
import app.splitevenly.data.db.entity.FxRateEntity
import app.splitevenly.data.db.entity.GroupEntity
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
import kotlinx.serialization.serializerOrNull
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the `@Serializable` partition over the 30 Room entities: every synced or pull-only wire mirror
 * has a generated serializer, and no device-local entity does. The annotation is therefore an exact
 * discriminator for "is a wire mirror" — a claim `data/AGENTS.md` relies on, and one that has already
 * been mis-documented in both directions once each. A new entity must land in exactly one list.
 *
 * JVM host test because serializer lookup by [KType] is reflection-backed here and reliable; the DAOs
 * themselves are covered on the iOS target.
 */
class EntitySerializablePartitionTest {
    private val wireEntities: Map<KClass<*>, KType> =
        mapOf(
            UserEntity::class to typeOf<UserEntity>(),
            GroupEntity::class to typeOf<GroupEntity>(),
            MemberEntity::class to typeOf<MemberEntity>(),
            PlaceholderClaimAnswerEntity::class to typeOf<PlaceholderClaimAnswerEntity>(),
            ExpenseEntity::class to typeOf<ExpenseEntity>(),
            ShareEntity::class to typeOf<ShareEntity>(),
            SettlementEntity::class to typeOf<SettlementEntity>(),
            SettlementAllocationEntity::class to typeOf<SettlementAllocationEntity>(),
            ConflictEntity::class to typeOf<ConflictEntity>(),
            ExpenseEditConflictEntity::class to typeOf<ExpenseEditConflictEntity>(),
            CommentEntity::class to typeOf<CommentEntity>(),
            ExpenseBlockedUserEntity::class to typeOf<ExpenseBlockedUserEntity>(),
            ReceiptEntity::class to typeOf<ReceiptEntity>(),
            CategoryEntity::class to typeOf<CategoryEntity>(),
            HistoryEventEntity::class to typeOf<HistoryEventEntity>(),
            ExpenseItemEntity::class to typeOf<ExpenseItemEntity>(),
            ItemClaimEntity::class to typeOf<ItemClaimEntity>(),
            ItemShareEntity::class to typeOf<ItemShareEntity>(),
            BillParticipantEntity::class to typeOf<BillParticipantEntity>(),
            PendingItemEditEntity::class to typeOf<PendingItemEditEntity>(),
        )

    private val deviceLocalEntities: Map<KClass<*>, KType> =
        mapOf(
            ExpenseSyncStateEntity::class to typeOf<ExpenseSyncStateEntity>(),
            RowSyncStateEntity::class to typeOf<RowSyncStateEntity>(),
            SupersededNoticeEntity::class to typeOf<SupersededNoticeEntity>(),
            ReceiptUploadEntity::class to typeOf<ReceiptUploadEntity>(),
            FxRateEntity::class to typeOf<FxRateEntity>(),
            FxBakedEntity::class to typeOf<FxBakedEntity>(),
            FxCurrencyEntity::class to typeOf<FxCurrencyEntity>(),
            FeedbackOutboxEntity::class to typeOf<FeedbackOutboxEntity>(),
        )

    @Test
    fun everyWireEntity_isSerializable() {
        for ((klass, type) in wireEntities) {
            assertNotNull(
                serializerOrNull(type),
                "${klass.simpleName} is a synced/pull-only wire mirror and must be @Serializable",
            )
        }
    }

    @Test
    fun noDeviceLocalEntity_isSerializable() {
        for ((klass, type) in deviceLocalEntities) {
            assertNull(
                serializerOrNull(type),
                "${klass.simpleName} is device-local; adding @Serializable would break the " +
                    "annotation-is-a-wire-mirror discriminator (see data/AGENTS.md)",
            )
        }
    }
}
