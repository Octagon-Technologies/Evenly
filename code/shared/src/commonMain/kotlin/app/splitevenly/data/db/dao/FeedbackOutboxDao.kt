package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.splitevenly.data.db.entity.FeedbackOutboxEntity

/** Reads and drains the device-local feedback outbox. See [FeedbackOutboxEntity] for why it exists. */
@Dao
interface FeedbackOutboxDao {
    @Insert
    suspend fun insert(row: FeedbackOutboxEntity)

    /**
     * Oldest first, so a backlog goes out in the order it was written. Bounded because a drain runs on
     * every reconnect and an unbounded one would hold the network open behind a queue nobody is waiting
     * on; the next drain picks up the rest.
     */
    @Query("SELECT * FROM feedback_outbox ORDER BY created_at ASC LIMIT 20")
    suspend fun pending(): List<FeedbackOutboxEntity>

    @Query("DELETE FROM feedback_outbox WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE feedback_outbox SET attempts = attempts + 1 WHERE id = :id")
    suspend fun recordAttempt(id: String)

    @Query("DELETE FROM feedback_outbox")
    suspend fun clear()
}
