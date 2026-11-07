package com.vayunmathur.library.util

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Format helpers on the pre-API-33 fallback path (LocaleData measurement system). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LibraryFormatSdk32Test {

    @Test fun formatSpeedImperialAndMetricFallback() {
        val us = 10f.formatSpeed(Locale.US) // 10 m/s in mph
        assertTrue(us.contains("22.4"), us)
        val de = 10f.formatSpeed(Locale.GERMANY) // 10 m/s in km/h
        assertTrue(de.contains("36"), de)
        assertTrue(0f.formatSpeed(Locale.US).contains("0"), "zero speed")
    }

    @Test fun digitsAndCommasOnOlderSdk() {
        assertEquals("3.14", 3.14159.toStringDigits(2))
        assertEquals("-3.14", (-3.14159).toStringDigits(2))
        assertEquals("1,000,000", 1_000_000L.toStringCommas())
    }

    @Test fun localizedNamesOnOlderSdk() {
        assertEquals("January", localizedMonthNames(DateNameStyle.FULL, Locale.US).first())
        assertEquals(7, localeFirstDayOfWeek(Locale.US))
        assertEquals(1, localeFirstDayOfWeek(Locale.GERMANY))
    }
}
