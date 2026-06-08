package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.UserEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `users` (02 §3.2). Writes are `suspend` (one-shot); reads come in two flavours:
 * `suspend` for one-shot fetches and `Flow` for reactive streams the UI collects.
 *
 * `@Upsert` is the sync-friendly write: insert-or-update keyed on the primary key, so replaying a
 * server row that already exists locally is idempotent (no `OnConflictStrategy` juggling).
 */
@Dao
interface UserDao {

    @Upsert
    suspend fun upsert(user: UserEntity)

    @Upsert
    suspend fun upsertAll(users: List<UserEntity>)

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getById(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id")
    fun observeById(id: String): Flow<UserEntity?>

    /** Case-insensitive lookup (mirrors `citext`); used to resolve a sign-in email to its user. */
    @Query("SELECT * FROM users WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun findByEmail(email: String): UserEntity?

    /** Placeholders in a group, name-ordered, for the reconcile picker (AC-M1-030). */
    @Query(
        """
        SELECT * FROM users
        WHERE is_placeholder = 1 AND placeholder_group_id = :groupId
        ORDER BY display_name COLLATE NOCASE ASC
        """
    )
    fun observePlaceholdersInGroup(groupId: String): Flow<List<UserEntity>>

    /**
     * Case-insensitive count of a placeholder name within a group — drives the uniqueness guard in
     * AC-M1-021 ("Tyler" / "tyler" / "TYLER" collide; same name in another group does not).
     */
    @Query(
        """
        SELECT COUNT(*) FROM users
        WHERE is_placeholder = 1
          AND placeholder_group_id = :groupId
          AND display_name = :name COLLATE NOCASE
        """
    )
    suspend fun countPlaceholderName(groupId: String, name: String): Int
}
