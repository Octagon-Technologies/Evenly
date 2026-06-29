package da.chelimo.sharecost.domain.repository

import da.chelimo.sharecost.core.error.AppResult
import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.domain.expense.GroupCategory
import kotlinx.coroutines.flow.Flow

/**
 * Per-group expense categories with copy-on-write defaults (App_Overview / 02 §3.6).
 *
 * A group starts with no stored rows and inherits [da.chelimo.sharecost.domain.expense.CategoryDefaults].
 * The first mutating call materializes the full default set into the `categories` table, then applies
 * the change — so groups that never customize cost zero rows. After materialization every category
 * (including ones derived from defaults) is an ordinary editable/soft-deletable row.
 */
interface CategoryRepository {
    /** The group's effective categories — its stored rows, or the built-in defaults if it has none. */
    fun observeCategories(groupId: GroupId): Flow<List<GroupCategory>>

    /** Add a new custom category (materializes defaults first if needed). Returns the created category. */
    suspend fun addCategory(groupId: GroupId, label: String, iconToken: String, colorHex: Long): AppResult<GroupCategory>

    /** Rename a category by its per-group [key] (materializes defaults first if needed). */
    suspend fun renameCategory(groupId: GroupId, key: String, label: String): AppResult<Unit>

    /** Change a category's icon + color (materializes defaults first if needed). */
    suspend fun updateCategoryStyle(groupId: GroupId, key: String, iconToken: String, colorHex: Long): AppResult<Unit>

    /** Soft-delete a category (materializes defaults first if needed). Existing expenses keep their key. */
    suspend fun deleteCategory(groupId: GroupId, key: String): AppResult<Unit>
}
