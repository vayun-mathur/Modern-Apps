package com.vayunmathur.clock.intents

import com.vayunmathur.library.intents.clock.SetAlarmData
import com.vayunmathur.library.intents.clock.SetTimerData
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the assistant-intent DTO shapes the clock handlers consume. */
@RunWith(RobolectricTestRunner::class)
class AssistantIntentsTest {
    @Test
    fun setAlarmDataShape() {
        val data = SetAlarmData(hour = 7, minute = 30, label = "wake")
        assertEquals(7, data.hour)
        assertEquals(30, data.minute)
        assertEquals("wake", data.label)
        assertTrue(SetAlarmIntent() is com.vayunmathur.library.util.AssistantIntent<*, *>)
    }

    @Test
    fun setTimerDataShape() {
        val data = SetTimerData(seconds = 600, label = "tea")
        assertEquals(600, data.seconds)
        assertEquals("tea", data.label)
        assertTrue(SetTimerIntent() is com.vayunmathur.library.util.AssistantIntent<*, *>)
    }
}
