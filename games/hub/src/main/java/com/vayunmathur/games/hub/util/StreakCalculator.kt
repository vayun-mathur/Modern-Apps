package com.vayunmathur.games.hub.util

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity

object StreakCalculator {

    data class StreakResult(
        val currentStreak: Int,
        val longestStreak: Int
    )

    fun calculate(
        sessions: List<PlaySessionEntity>,
        minSessionMs: Long = 60_000L,
        now: Long = System.currentTimeMillis()
    ): StreakResult {
        if (sessions.isEmpty()) return StreakResult(0, 0)
        val sortedDays = qualifyingDays(sessions, minSessionMs)
        if (sortedDays.isEmpty()) return StreakResult(0, 0)
        return StreakResult(
            currentStreak = currentStreak(sortedDays, now),
            longestStreak = longestStreak(sortedDays),
        )
    }

    // Calendar days (not 24h instants): across a DST transition consecutive local
    // midnights are 23h/25h apart, so fixed-millisecond day arithmetic breaks
    // streaks twice a year. Comparing LocalDate epoch days is DST-proof.
    private fun qualifyingDays(sessions: List<PlaySessionEntity>, minSessionMs: Long): List<LocalDate> {
        val tz = TimeZone.currentSystemDefault()
        val qualifyingDays = mutableSetOf<LocalDate>()
        for (s in sessions) {
            if (isQualifyingSession(s, minSessionMs)) {
                qualifyingDays.add(
                    Instant.fromEpochMilliseconds(s.startTime).toLocalDateTime(tz).date
                )
            }
        }
        return qualifyingDays.sorted()
    }

    private fun isQualifyingSession(s: PlaySessionEntity, minSessionMs: Long): Boolean = when {
        s.durationMs != null -> s.durationMs >= minSessionMs
        s.endTime != null -> true
        else -> false
    }

    private fun longestStreak(sortedDays: List<LocalDate>): Int {
        var maxStreak = 1
        var curRun = 1
        for (i in 1 until sortedDays.size) {
            if (sortedDays[i].toEpochDays() - sortedDays[i - 1].toEpochDays() == 1L) {
                curRun++
                if (curRun > maxStreak) maxStreak = curRun
            } else {
                curRun = 1
            }
        }
        return maxStreak
    }

    private fun currentStreak(sortedDays: List<LocalDate>, now: Long): Int {
        val tz = TimeZone.currentSystemDefault()
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(tz).date
        val lastDay = sortedDays.last()
        val lastEpochDay = lastDay.toEpochDays()
        val todayEpochDay = today.toEpochDays()
        if (lastEpochDay != todayEpochDay && lastEpochDay != todayEpochDay - 1) return 0
        var streak = 1
        var idx = sortedDays.lastIndex - 1
        var expectedEpochDay = lastEpochDay - 1
        while (idx >= 0 && sortedDays[idx].toEpochDays() >= expectedEpochDay) {
            if (sortedDays[idx].toEpochDays() == expectedEpochDay) {
                streak++
                expectedEpochDay--
            }
            idx--
        }
        return streak
    }
}
