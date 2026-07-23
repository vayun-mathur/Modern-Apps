package com.vayunmathur.games.hub.util

import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import java.util.Calendar
import java.util.concurrent.TimeUnit

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

        val qualifyingDays = mutableSetOf<Long>()
        for (s in sessions) {
            val qualifies = when {
                s.durationMs != null -> s.durationMs >= minSessionMs
                s.endTime != null -> true
                else -> false
            }
            if (!qualifies) continue
            qualifyingDays.add(dayStart(s.startTime))
        }

        if (qualifyingDays.isEmpty()) return StreakResult(0, 0)

        val sortedDays = qualifyingDays.sorted()

        var maxStreak = 1
        var curRun = 1
        for (i in 1 until sortedDays.size) {
            if (sortedDays[i] - sortedDays[i - 1] == TimeUnit.DAYS.toMillis(1)) {
                curRun++
                if (curRun > maxStreak) maxStreak = curRun
            } else {
                curRun = 1
            }
        }

        val todayStart = dayStart(now)
        val yesterdayStart = todayStart - TimeUnit.DAYS.toMillis(1)

        var currentStreak = 0
        val lastDay = sortedDays.last()
        if (lastDay == todayStart || lastDay == yesterdayStart) {
            currentStreak = 1
            var idx = sortedDays.lastIndex - 1
            var expectedDay = lastDay - TimeUnit.DAYS.toMillis(1)
            while (idx >= 0) {
                if (sortedDays[idx] == expectedDay) {
                    currentStreak++
                    expectedDay -= TimeUnit.DAYS.toMillis(1)
                    idx--
                } else if (sortedDays[idx] < expectedDay) {
                    break
                } else {
                    idx--
                }
            }
        }

        return StreakResult(currentStreak, maxStreak)
    }

    private fun dayStart(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
