package com.vayunmathur.youpipe.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.runner.RunWith

/** Slice 2/3 pass 3: every countString magnitude and uploadTimeAgo date bucket. */
@RunWith(AndroidJUnit4::class)
class FormattingMorePass3Test {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun countString_everyMagnitude() {
        assertEquals("5", countString(context, 5))
        assertEquals("999", countString(context, 999))
        assertEquals("-1", countString(context, -1))
        // 4-6 digits: thousands, with shrinking precision.
        assertTrue(countString(context, 1_234).endsWith("K"), countString(context, 1_234))
        assertTrue(countString(context, 12_345).endsWith("K"), countString(context, 12_345))
        assertEquals("123K", countString(context, 123_456).replace(".0", ""))
        // 7-9 digits: millions.
        assertTrue(countString(context, 1_234_567).endsWith("M"))
        assertTrue(countString(context, 12_345_678).endsWith("M"))
        assertTrue(countString(context, 123_456_789).endsWith("M"))
        // 10-12 digits: billions.
        assertTrue(countString(context, 1_234_567_890).endsWith("B"))
        assertTrue(countString(context, 12_345_678_901).endsWith("B"))
        assertTrue(countString(context, 123_456_789_012).endsWith("B"))
        // 13+ digits fall back to the raw number.
        assertEquals("1234567890123", countString(context, 1_234_567_890_123))
    }

    @Test
    fun uploadTimeAgo_localDateBuckets() {
        val tz = TimeZone.currentSystemDefault()
        fun dateDaysAgo(days: Int) = (Clock.System.now() - days.days).toLocalDateTime(tz).date
        assertEquals("Just now", uploadTimeAgo(context, dateDaysAgo(0)))
        val days = uploadTimeAgo(context, dateDaysAgo(3))
        assertTrue(days.contains("3") && days.contains("days"), days)
        val months = uploadTimeAgo(context, dateDaysAgo(65))
        assertTrue(months.contains("months") || months.contains("days"), months)
        val years = uploadTimeAgo(context, dateDaysAgo(800))
        assertTrue(years.contains("years"), years)
    }

    @Test
    fun uploadTimeAgo_instantDelegatesToDateForOldUploads() {
        val now = Clock.System.now()
        val old = uploadTimeAgo(context, now - 10.days)
        assertTrue(old.contains("10") && old.contains("days"), old)
    }
}
