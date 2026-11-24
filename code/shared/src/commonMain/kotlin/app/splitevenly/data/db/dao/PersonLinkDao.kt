package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.PersonLinkEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `person_links`, the owner's private "these two names are one person" statements. */
@Dao
interface PersonLinkDao {
    @Upsert
    suspend fun upsert(link: PersonLinkEntity)

    @Upsert
    suspend fun upsertAll(links: List<PersonLinkEntity>)

    /** Every local row, tombstones included: the push side of sync. */
    @Query("SELECT * FROM person_links")
    suspend fun allForSync(): List<PersonLinkEntity>

    @Query("SELECT * FROM person_links WHERE owner_user_id = :ownerUserId AND deleted_at IS NULL")
    fun observeLive(ownerUserId: String): Flow<List<PersonLinkEntity>>

    @Query("SELECT * FROM person_links WHERE owner_user_id = :ownerUserId AND deleted_at IS NULL")
    suspend fun live(ownerUserId: String): List<PersonLinkEntity>

    @Query(
        "UPDATE person_links SET deleted_at = :ts, updated_at = :ts, row_version = row_version + 1 " +
            "WHERE id IN (:ids) AND deleted_at IS NULL",
    )
    suspend fun softDelete(ids: List<String>, ts: Long)
}
