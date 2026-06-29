package da.chelimo.sharecost.data.repository

import da.chelimo.sharecost.core.error.AppError
import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.error.asErr
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.time.nowEpochMillis
import da.chelimo.sharecost.data.db.dao.CategoryDao
import da.chelimo.sharecost.data.db.entity.CategoryEntity
import da.chelimo.sharecost.domain.expense.CategoryDefaults
import da.chelimo.sharecost.domain.expense.GroupCategory
import da.chelimo.sharecost.domain.repository.CategoryRepository
import da.chelimo.sharecost.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Local-first [CategoryRepository] with copy-on-write defaults. Reads stream from Room; an empty stored
 * set is mapped to [CategoryDefaults]. The first mutating call [materialize]s the defaults into the
 * `categories` table (one-time, per group) so the edit has concrete rows to act on; sync carries the
 * rows to Supabase. Soft-delete only (Rule 1) — `allForSync` ships tombstones.
 */
@OptIn(ExperimentalTime::class)
class CategoryRepositoryImpl(
    private val categoryDao: CategoryDao,
    private val clock: Clock = Clock.System,
) : CategoryRepository {

    override fun observeCategories(groupId: GroupId): Flow<List<GroupCategory>> =
        categoryDao.observeByGroup(groupId.value).map { rows ->
            if (rows.isEmpty()) CategoryDefaults.all
            else rows.map { it.toDomain() }
        }

    override suspend fun addCategory(
        groupId: GroupId,
        label: String,
        iconToken: String,
        colorHex: Long,
    ): AppResult<GroupCategory> {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return validationErr("label", AppError.Validation.Reason.Required)
        val now = clock.nowEpochMillis()
        materialize(groupId.value, now)
        val sortOrder = categoryDao.maxSortOrder(groupId.value) + 1
        val entity = CategoryEntity(
            id = newId(),
            groupId = groupId.value,
            key = newId(),
            label = trimmed,
            icon = iconToken,
            color = colorHex.toColorHex(),
            sortOrder = sortOrder,
            isDefault = false,
            createdAt = now,
            updatedAt = now,
        )
        categoryDao.upsert(entity)
        return AppResult.Ok(entity.toDomain())
    }

    override suspend fun renameCategory(groupId: GroupId, key: String, label: String): AppResult<Unit> {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return validationErr("label", AppError.Validation.Reason.Required)
        return mutateExisting(groupId, key) { it.copy(label = trimmed) }
    }

    override suspend fun updateCategoryStyle(
        groupId: GroupId,
        key: String,
        iconToken: String,
        colorHex: Long,
    ): AppResult<Unit> = mutateExisting(groupId, key) {
        it.copy(icon = iconToken, color = colorHex.toColorHex())
    }

    override suspend fun deleteCategory(groupId: GroupId, key: String): AppResult<Unit> {
        val now = clock.nowEpochMillis()
        materialize(groupId.value, now)
        categoryDao.softDelete(groupId.value, key, now)
        return AppResult.Ok(Unit)
    }

    /**
     * Apply [transform] to the live row for [key], bumping `updated_at`/`row_version`. Materializes the
     * group's defaults first so a never-customized group's edit has a concrete row to change.
     */
    private suspend inline fun mutateExisting(
        groupId: GroupId,
        key: String,
        transform: (CategoryEntity) -> CategoryEntity,
    ): AppResult<Unit> {
        val now = clock.nowEpochMillis()
        materialize(groupId.value, now)
        val current = categoryDao.getByKey(groupId.value, key)
            ?: return validationErr("category", AppError.Validation.Reason.Required)
        categoryDao.upsert(transform(current).copy(updatedAt = now, rowVersion = current.rowVersion + 1))
        return AppResult.Ok(Unit)
    }

    /**
     * One-time copy-on-write: if the group has no stored categories yet, seed the full default set as
     * rows (deterministic ids so a re-run is idempotent). No-op once any row exists.
     */
    private suspend fun materialize(groupId: String, now: Long) {
        if (categoryDao.countActiveInGroup(groupId) > 0) return
        categoryDao.upsertAll(
            CategoryDefaults.all.map { d ->
                CategoryEntity(
                    id = "${groupId}__${d.key}",
                    groupId = groupId,
                    key = d.key,
                    label = d.label,
                    icon = d.iconToken,
                    color = d.colorHex.toColorHex(),
                    sortOrder = d.sortOrder.toLong(),
                    isDefault = true,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )
    }

    private fun validationErr(field: String, reason: AppError.Validation.Reason): AppResult<Nothing> =
        AppError.Validation(mapOf(field to reason)).asErr()
}

private fun CategoryEntity.toDomain(): GroupCategory = GroupCategory(
    key = key,
    label = label,
    iconToken = icon,
    colorHex = color.toColorLong(),
    isDefault = isDefault,
    sortOrder = sortOrder.toInt(),
)

/** ARGB long (e.g. 0xFF2563EB) → `#AARRGGBB` hex string for storage. */
private fun Long.toColorHex(): String =
    "#" + (this and 0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase()

/** `#AARRGGBB` or `#RRGGBB` hex string → ARGB long (opaque if alpha omitted). Falls back to slate grey. */
private fun String.toColorLong(): Long {
    val hex = removePrefix("#")
    val argb = when (hex.length) {
        6 -> "FF$hex"
        8 -> hex
        else -> return 0xFF94A3B8L
    }
    return argb.toLongOrNull(16) ?: 0xFF94A3B8L
}
