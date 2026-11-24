package com.vayunmathur.clock.wear.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchTimerStateTest {

    @Test
    fun runningTimerMeasuresOnMonotonicBase() {
        val timer = WatchTimer(
            id = 1L, totalMillis = 300_000L, remainingMillis = 300_000L,
            startWallMillis = 1_000_000L, startElapsedMillis = 500_000L, running = true,
        )
        // Wall jumped an hour ahead; monotonic says 60s passed -> 240s remain.
        assertEquals(
            240_000L,
            watchTimerRemainingMillis(timer, wallNowMillis = 4_600_000L, elapsedNowMillis = 560_000L),
        )
    }

    @Test
    fun afterRebootTimerFallsBackToWallBase() {
        val timer = WatchTimer(
            id = 1L, totalMillis = 300_000L, remainingMillis = 300_000L,
            startWallMillis = 1_000_000L, startElapsedMillis = 500_000L, running = true,
        )
        // Monotonic reset by reboot (now < anchor): wall delta rules.
        assertEquals(
            180_000L,
            watchTimerRemainingMillis(timer, wallNowMillis = 1_120_000L, elapsedNowMillis = 5_000L),
        )
    }

    @Test
    fun pauseBanksRemainderAndExpiryClampsToZero() {
        val timer = WatchTimer(
            id = 1L, totalMillis = 300_000L, remainingMillis = 300_000L,
            startWallMillis = 1_000_000L, startElapsedMillis = 500_000L, running = true,
        )
        val paused = pauseWatchTimer(timer, wallNowMillis = 1_060_000L, elapsedNowMillis = 560_000L)
        assertEquals(240_000L, paused.remainingMillis)
        assertEquals(false, paused.running)
        assertEquals(
            0L,
            watchTimerRemainingMillis(timer, wallNowMillis = 9_000_000L, elapsedNowMillis = 9_000_000L),
        )
    }

    @Test
    fun stopwatchBanksAndResumes() {
        var stopwatch = startWatchStopwatch(WatchStopwatch(), 1_000_000L, 500_000L)
        stopwatch = pauseWatchStopwatch(stopwatch, 1_061_500L, 561_500L)
        assertEquals(61_500L, stopwatch.bankedMillis)
        stopwatch = startWatchStopwatch(stopwatch, 2_000_000L, 10_000L)
        assertEquals(
            71_500L,
            watchStopwatchElapsedMillis(stopwatch, wallNowMillis = 2_010_000L, elapsedNowMillis = 20_000L),
        )
    }

    @Test
    fun formatting() {
        assertEquals("05:00", formatWatchDuration(300_000L))
        assertEquals("1:05:09", formatWatchDuration(3_909_000L))
        assertEquals("01:01.9", formatWatchStopwatch(61_900L))
        assertEquals("7:05 AM", formatWearAlarmTime(7, 5))
        assertEquals("12:00 PM", formatWearAlarmTime(12, 0))
        assertEquals("Weekdays", formatWearAlarmDays(0b0111110))
        assertEquals("Once", formatWearAlarmDays(0))
    }
}
