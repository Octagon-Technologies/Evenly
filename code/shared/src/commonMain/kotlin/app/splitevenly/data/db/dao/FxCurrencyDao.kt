package app.splitevenly.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.splitevenly.data.db.entity.FxCurrencyEntity

/** DAO for the local-only `fx_currencies` cache (02 §3.14 area, 04 §5.x). */
@Dao
interface FxCurrencyDao {

    @Upsert
    suspend fun upsertAll(currencies: List<FxCurrencyEntity>)

    @Query("SELECT * FROM fx_currencies ORDER BY code")
    suspend fun all(): List<FxCurrencyEntity>
}
