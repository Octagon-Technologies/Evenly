package com.sharecost.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {

    @Test
    fun validMoneyConstructsAndExposesFields() {
        // 03-business-rules.md §1.1 — a valid Money constructs and exposes its fields
        val money = Money(amountSubunits = 1500, currency = "USD")
        assertEquals(1500, money.amountSubunits)
        assertEquals("USD", money.currency)
    }

    @Test
    fun negativeAmountThrows() {
        // 03-business-rules.md §1.1 — amountSubunits invariant: never negative (require >= 0)
        assertFailsWith<IllegalArgumentException> {
            Money(amountSubunits = -1, currency = "USD")
        }
    }

    @Test
    fun zeroAmountIsAllowed() {
        // 03-business-rules.md §1.1 — zero is a valid (non-negative) amount
        val money = Money(amountSubunits = 0, currency = "EUR")
        assertEquals(0, money.amountSubunits)
        assertEquals("EUR", money.currency)
    }
}
