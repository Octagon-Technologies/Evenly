package app.splitevenly.data.repository

import app.splitevenly.core.error.AppResult
import app.splitevenly.data.auth.StubAuthSession
import app.splitevenly.data.db.EvenlyDatabase
import app.splitevenly.data.db.inMemoryTestDatabase
import app.splitevenly.domain.settlement.PaymentApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ProfileRepositoryImpl]: payment-handle edits persist, blanks are cleared, and only-set apps fold in. */
class ProfileRepositoryTest {

    private lateinit var db: EvenlyDatabase
    private lateinit var auth: StubAuthSession
    private lateinit var repo: ProfileRepositoryImpl

    @BeforeTest
    fun setUp() {
        db = inMemoryTestDatabase()
        auth = StubAuthSession(db.userDao())
        repo = ProfileRepositoryImpl(db.userDao(), auth)
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun updatePaymentHandles_persistsSetApps_clearsBlanks() = runTest {
        auth.signIn("Alex")

        val result = repo.updatePaymentHandles(
            mapOf(
                PaymentApp.VENMO to "@alex-r",
                PaymentApp.CASH_APP to "   ",      // blank → cleared
                PaymentApp.PAYPAL to "paypal.me/alex",
            ),
        )
        assertTrue(result is AppResult.Ok)

        val profile = repo.observeProfile().first { it != null }!!
        assertEquals("@alex-r", profile.paymentHandles[PaymentApp.VENMO])
        assertEquals("paypal.me/alex", profile.paymentHandles[PaymentApp.PAYPAL])
        assertFalse(profile.paymentHandles.containsKey(PaymentApp.CASH_APP))
        assertFalse(profile.paymentHandles.containsKey(PaymentApp.ZELLE))
    }

    @Test
    fun updatePaymentHandles_signedOut_returnsError() = runTest {
        assertTrue(repo.updatePaymentHandles(mapOf(PaymentApp.VENMO to "@x")) is AppResult.Err)
    }
}
