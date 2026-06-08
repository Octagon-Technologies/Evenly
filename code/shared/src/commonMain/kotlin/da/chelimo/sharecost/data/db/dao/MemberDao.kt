package da.chelimo.sharecost.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import da.chelimo.sharecost.data.db.entity.MemberEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `members` (02 §3.5). */
@Dao
interface MemberDao {

    @Upsert
    suspend fun upsert(member: MemberEntity)

    @Upsert
    suspend fun upsertAll(members: List<MemberEntity>)

    @Query("SELECT * FROM members WHERE group_id = :groupId AND user_id = :userId")
    suspend fun getMember(groupId: String, userId: String): MemberEntity?

    @Query(
        """
        SELECT * FROM members
        WHERE group_id = :groupId AND status = 'ACTIVE'
        ORDER BY joined_at ASC
        """
    )
    fun observeActiveMembers(groupId: String): Flow<List<MemberEntity>>

    @Query("SELECT COUNT(*) FROM members WHERE group_id = :groupId AND status = 'ACTIVE'")
    suspend fun countActiveMembers(groupId: String): Int

    /**
     * Active members oldest-join first — feeds the admin-transfer rule (03 §7.5: admin passes to
     * the longest-tenured active member).
     */
    @Query(
        """
        SELECT * FROM members
        WHERE group_id = :groupId AND status = 'ACTIVE'
        ORDER BY joined_at ASC, id ASC
        """
    )
    suspend fun activeMembersByTenure(groupId: String): List<MemberEntity>
}
