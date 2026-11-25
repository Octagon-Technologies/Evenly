package app.splitevenly.domain.settlement

import app.splitevenly.core.id.GroupId
import app.splitevenly.core.id.SettlementId
import app.splitevenly.core.id.UserId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A payment proof hangs off the payment's chain id (docs/features/payment-proof.md): an edited payment is a
 * new row that names the first one, and its screenshot must still be found.
 */
class SettlementChainIdTest {
    private fun record(
        id: String,
        original: String?,
    ) = SettlementRecord(
        id = SettlementId(id),
        groupId = GroupId("g"),
        fromUserId = UserId("a"),
        toUserId = UserId("b"),
        paymentCurrency = "USD",
        paymentAmountSubunits = 100,
        settledAt = 0,
        notes = null,
        originalSettlementId = original?.let { SettlementId(it) },
    )

    @Test
    fun neverEdited_isItsOwnChain() = assertEquals("s1", record("s1", null).chainId)

    @Test
    fun editedPayment_keepsTheFirstPaymentsId() = assertEquals("s1", record("s3", "s1").chainId)
}
