package da.chelimo.sharecost.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `fx_currencies` — a Room-ONLY, local cache of the FX provider's `/currencies` list (04 §5.x). The
 * code/name set is effectively static, so it's fetched once (not on the daily `fx_rates` refresh
 * schedule) and served from here after. No server counterpart.
 */
@Entity(tableName = "fx_currencies")
data class FxCurrencyEntity(
    @PrimaryKey
    @ColumnInfo(name = "code")
    val code: String,

    @ColumnInfo(name = "name")
    val name: String,
)
