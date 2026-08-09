package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.GroupScanUsageEntity
import kotlinx.coroutines.flow.Flow

/** DAO for the device-local free-scan-count cache. No `allForSync`: this table never leaves the device. */
@Dao
interface GroupScanUsageDao {

    @Upsert
    suspend fun upsert(usage: GroupScanUsageEntity)

    @Query("SELECT * FROM group_scan_usage WHERE group_id = :groupId LIMIT 1")
    fun observe(groupId: String): Flow<GroupScanUsageEntity?>
}
