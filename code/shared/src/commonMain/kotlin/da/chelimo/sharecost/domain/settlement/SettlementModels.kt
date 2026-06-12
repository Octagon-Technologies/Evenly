package da.chelimo.sharecost.domain.settlement

import da.chelimo.sharecost.core.id.GroupId
import da.chelimo.sharecost.core.id.SettlementId
import da.chelimo.sharecost.core.id.UserId

/**
 * Domain view of a settlement (02 §3.9): a payment event from [fromUserId] to [toUserId] that pays
 * down one or more of their shares. The allocation detail lives in the data layer; this is the
 * summary the UI lists (05 §8).
 */
data class SettlementRecord(
    val id: SettlementId,
    val groupId: GroupId,
    val fromUserId: UserId,
    val toUserId: UserId,
    val paymentCurrency: String,
    val paymentAmountSubunits: Long,
    val settledAt: Long,
    val notes: String?,
)

/**
 * Input to [da.chelimo.sharecost.domain.repository.SettlementRepository.applySettlement]
 * (04 §2.3 `apply_settlement`). The repository computes the per-share allocations itself
 * (03 §4.3.1, oldest-share-first) — same currency only for MVP; cross-FX is v1.1 (03 §4.3.2).
 */
data class NewSettlement(
    val groupId: GroupId,
    val fromUserId: UserId,
    val toUserId: UserId,
    val paymentCurrency: String,
    val paymentAmountSubunits: Long,
    val createdBy: UserId,
    val notes: String? = null,
    val paymentApp: String? = null,
)
