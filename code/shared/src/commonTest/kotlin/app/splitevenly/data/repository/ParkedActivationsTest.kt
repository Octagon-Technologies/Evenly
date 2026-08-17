package app.splitevenly.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The identity a parked charge carries (finding R11).
 *
 * The park itself was never the problem — it is what makes a retry free, and it is written before the
 * activate call for exactly that reason. What was missing is *which group* the charge belongs to: with
 * one un-scoped record, a stuck charge in group A answered the pass sheet opened in group B, which then
 * either activated A and dismissed itself as though B were Pro, or replaced B's Buy button with a retry
 * that could never buy B anything.
 */
class ParkedActivationsTest {
    @Test
    fun aDeviceHoldingTheOldSingleRecord_readsItAsThatGroupsCharge() {
        // The pre-fix format was exactly one `groupId|txnId` line, so it decodes with no migration step.
        val parked = ParkedActivations.decode("groupA|txn123")

        assertEquals("txn123", parked.txnFor("groupA"))
        assertNull(parked.txnFor("groupB"), "and it answers for nobody else")
    }

    @Test
    fun twoGroupsCanBeWaitingAtOnce_andEachAnswersOnlyForItself() {
        val parked =
            ParkedActivations
                .decode(null)
                .with("groupA", "txnA")
                .with("groupB", "txnB")

        assertEquals("txnA", parked.txnFor("groupA"))
        assertEquals("txnB", parked.txnFor("groupB"))
        assertEquals(setOf("groupA", "groupB"), parked.groupIds())
        assertEquals(parked, ParkedActivations.decode(parked.encode()), "and the pair survives the round trip")
    }

    @Test
    fun activatingOneGroup_leavesTheOtherGroupsChargeParked() {
        val parked = ParkedActivations.decode(null).with("groupA", "txnA").with("groupB", "txnB")

        val afterA = parked.without("groupA")

        assertNull(afterA.txnFor("groupA"))
        assertEquals("txnB", afterA.txnFor("groupB"), "B is still owed a pass")
        assertTrue(afterA.without("groupB").isEmpty(), "and the store empties once both land")
    }

    @Test
    fun buyingAgainForTheSameGroup_replacesThatGroupsRecordRatherThanStackingOne() {
        val parked = ParkedActivations.decode("groupA|old").with("groupA", "new")

        assertEquals("new", parked.txnFor("groupA"))
        assertEquals(1, parked.groupIds().size)
    }

    @Test
    fun anUnreadableRecord_isDroppedWithoutTakingTheReadableOnesWithIt() {
        // A value we cannot parse is unusable: retrying it forever is the failure mode to avoid, but it
        // must not cost another group the charge it is genuinely owed.
        val parked = ParkedActivations.decode("garbage\ngroupB|txnB\n|\n\ngroupC|")

        assertEquals(setOf("groupB"), parked.groupIds())
        assertEquals("txnB", parked.txnFor("groupB"))
    }
}
