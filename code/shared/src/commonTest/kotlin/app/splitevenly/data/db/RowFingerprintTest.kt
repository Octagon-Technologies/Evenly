package app.splitevenly.data.db

import app.splitevenly.data.db.entity.UserEntity
import app.splitevenly.data.db.entity.rowFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the 64-bit row fingerprint dirty detection rides on. A row whose edit lands on the same
 * fingerprint is silently never pushed and there is no repair path, so the fingerprint must survive
 * exactly the case a 32-bit `hashCode()` cannot: two rows whose data-class hashCodes collide.
 */
class RowFingerprintTest {
    private fun user(name: String) =
        UserEntity(
            id = "u1",
            displayName = name,
            createdAt = 1L,
            updatedAt = 2L,
        )

    @Test
    fun equalRows_haveEqualFingerprints() {
        assertEquals(rowFingerprint(user("Ama")), rowFingerprint(user("Ama")))
    }

    @Test
    fun differentRows_haveDifferentFingerprints() {
        assertNotEquals(rowFingerprint(user("Ama")), rowFingerprint(user("Ben")))
    }

    /**
     * "Aa" and "BB" share a String.hashCode, so the two rows' data-class hashCodes collide — the exact
     * edit a 32-bit fingerprint reads as "already pushed". The premise is asserted, not assumed.
     */
    @Test
    fun hashCodeCollidingEdit_stillChangesTheFingerprint() {
        val before = user("Aa")
        val after = user("BB")
        assertEquals(before.hashCode(), after.hashCode(), "test premise: these rows must collide on hashCode")
        assertNotEquals(rowFingerprint(before), rowFingerprint(after))
    }
}
