package app.splitevenly.domain.currency

import kotlin.test.Test
import kotlin.test.assertEquals

class StartingCurrencyTest {
    @Test fun kenyaDeviceGetsShillings() = assertEquals("KES", startingCurrency("USD", "KES"))

    @Test fun unsupportedRegionFallsBackToUsd() = assertEquals("USD", startingCurrency("USD", "BRL"))

    @Test fun unknownRegionFallsBackToUsd() = assertEquals("USD", startingCurrency(null, null))

    @Test fun storedChoiceBeatsDevice() = assertEquals("EUR", startingCurrency("EUR", "KES"))
}
