package com.vayunmathur.clock.wear.domain

import kotlinx.serialization.Serializable

/**
 * A watch-native countdown timer. It is created, armed (with the watch's
 * AlarmManager), paused and rung entirely on the watch; the phone is never
 * involved. Persisted with dual wall/monotonic anchors like the phone's
 * timers: remaining time is measured on the monotonic base while it is
 * usable, and on the wall base after a reboot reset it.
 */
@Serializable
data class WatchTimer(
    val id: Long,
    val label: String = "",
    val totalMillis: Long,
    /** Remaining when (last) started, or the frozen remainder while paused. */
    val remainingMillis: Long,
    val startWallMillis: Long = 0L,
    val startElapsedMillis: Long? = null,
    val running: Boolean = false,
    val finished: Boolean = false,
)

/** Elapsed on the dual base: monotonic when usable, else wall (clamped >= 0). */
fun dualElapsedMillis(
    startWallMillis: Long,
    startElapsedMillis: Long?,
    wallNowMillis: Long,
    elapsedNowMillis: Long?,
): Long = if (startElapsedMillis != null && elapsedNowMillis != null &&
    elapsedNowMillis >= startElapsedMillis
) {
    elapsedNowMillis - startElapsedMillis
} else {
    (wallNowMillis - startWallMillis).coerceAtLeast(0L)
}

/** Live remaining for [timer]; 0 when finished or expired. */
fun watchTimerRemainingMillis(
    timer: WatchTimer,
    wallNowMillis: Long,
    elapsedNowMillis: Long?,
): Long {
    if (timer.finished) return 0L
    if (!timer.running) return timer.remainingMillis.coerceAtLeast(0L)
    val elapsed = dualElapsedMillis(
        timer.startWallMillis, timer.startElapsedMillis, wallNowMillis, elapsedNowMillis,
    )
    return (timer.remainingMillis - elapsed).coerceAtLeast(0L)
}

/** Wall instant at which a running [timer] ends (for RTC fallback arming). */
fun watchTimerEndWallMillis(timer: WatchTimer): Long =
    timer.startWallMillis + timer.remainingMillis

/** Start/resume: re-anchor both bases at now. */
fun startWatchTimer(timer: WatchTimer, wallNowMillis: Long, elapsedNowMillis: Long?): WatchTimer =
    timer.copy(
        running = true,
        finished = false,
        startWallMillis = wallNowMillis,
        startElapsedMillis = elapsedNowMillis,
    )

/** Pause: bank the live remainder and drop the anchors. */
fun pauseWatchTimer(timer: WatchTimer, wallNowMillis: Long, elapsedNowMillis: Long?): WatchTimer =
    timer.copy(
        running = false,
        remainingMillis = watchTimerRemainingMillis(timer, wallNowMillis, elapsedNowMillis),
        startElapsedMillis = null,
    )

/** A watch-native stopwatch: banked time plus a live dual-base run segment. */
@Serializable
data class WatchStopwatch(
    val running: Boolean = false,
    val bankedMillis: Long = 0L,
    val startWallMillis: Long = 0L,
    val startElapsedMillis: Long? = null,
    val lapsMillis: List<Long> = emptyList(),
)

/** Live elapsed for [stopwatch]. */
fun watchStopwatchElapsedMillis(
    stopwatch: WatchStopwatch,
    wallNowMillis: Long,
    elapsedNowMillis: Long?,
): Long {
    if (!stopwatch.running) return stopwatch.bankedMillis
    return stopwatch.bankedMillis + dualElapsedMillis(
        stopwatch.startWallMillis, stopwatch.startElapsedMillis, wallNowMillis, elapsedNowMillis,
    )
}

/** Start/resume the stopwatch segment. */
fun startWatchStopwatch(
    stopwatch: WatchStopwatch,
    wallNowMillis: Long,
    elapsedNowMillis: Long?,
): WatchStopwatch = stopwatch.copy(
    running = true, startWallMillis = wallNowMillis, startElapsedMillis = elapsedNowMillis,
)

/** Pause: bank the live elapsed and stop the segment. */
fun pauseWatchStopwatch(
    stopwatch: WatchStopwatch,
    wallNowMillis: Long,
    elapsedNowMillis: Long?,
): WatchStopwatch = stopwatch.copy(
    running = false,
    bankedMillis = watchStopwatchElapsedMillis(stopwatch, wallNowMillis, elapsedNowMillis),
    startElapsedMillis = null,
)

/** Preset timer lengths offered on the watch, in millis. */
val WATCH_TIMER_PRESETS_MILLIS: List<Long> =
    listOf(60_000L, 300_000L, 600_000L, 900_000L, 1_800_000L, 3_600_000L)

/** "1:05:09" / "05:09" / "0:09" for a duration in millis (never negative). */
fun formatWatchDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** "1:05:09.3" stopwatch form with tenths (floored, unlike the timer's ceil display). */
fun formatWatchStopwatch(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val tenths = (millis.coerceAtLeast(0L) / 100) % 10
    val base = if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
    return "$base.$tenths"
}

/** "7:05 AM" for an alarm time. */
fun formatWearAlarmTime(hour: Int, minute: Int): String {
    val suffix = if (hour < 12) "AM" else "PM"
    val hour12 = if (hour % 12 == 0) 12 else hour % 12
    return "%d:%02d %s".format(hour12, minute, suffix)
}

/** "Every day" / "Weekdays" / "Mon Wed Fri" / "" (one-shot) for a days mask, bit 0 = Sunday. */
fun formatWearAlarmDays(daysMask: Int): String {
    if (daysMask == 0) return "Once"
    if (daysMask == 0b1111111) return "Every day"
    if (daysMask == 0b0111110) return "Weekdays"
    val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    return names.filterIndexed { index, _ -> daysMask and (1 shl index) != 0 }.joinToString(" ")
}
