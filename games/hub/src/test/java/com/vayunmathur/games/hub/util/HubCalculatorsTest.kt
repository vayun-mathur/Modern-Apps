package com.vayunmathur.games.hub.util

import androidx.compose.ui.graphics.Color
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** XP levels, streaks and formatting helpers. */
class HubCalculatorsTest {

    @Test
    fun xpLevelsThresholdsAndProgress() {
        assertEquals(1, XpLevelCalculator.level(0))
        assertEquals(1, XpLevelCalculator.level(-50))
        assertEquals(1, XpLevelCalculator.level(99))
        assertEquals(2, XpLevelCalculator.level(100))
        assertEquals(3, XpLevelCalculator.level(400))
        assertEquals(5, XpLevelCalculator.level(1600))
        assertEquals(0, XpLevelCalculator.xpForLevel(1))
        assertEquals(100, XpLevelCalculator.xpForLevel(2))
        assertEquals(400, XpLevelCalculator.xpForLevel(3))
        assertEquals(100, XpLevelCalculator.xpToNextLevel(0))
        assertEquals(300, XpLevelCalculator.xpToNextLevel(100))
        assertEquals(0f, XpLevelCalculator.progressToNextLevel(100))
        assertEquals(0.5f, XpLevelCalculator.progressToNextLevel(250), 0.001f)
        assertTrue(XpLevelCalculator.progressToNextLevel(399) in 0.99f..1f)
    }

    @Test
    fun xpTitlesByBand() {
        assertEquals("Beginner", XpLevelCalculator.title(1))
        assertEquals("Novice", XpLevelCalculator.title(2))
        assertEquals("Casual Gamer", XpLevelCalculator.title(4))
        assertEquals("Enthusiast", XpLevelCalculator.title(8))
        assertEquals("Master", XpLevelCalculator.title(12))
        assertEquals("Grandmaster", XpLevelCalculator.title(18))
        assertEquals("Legend", XpLevelCalculator.title(25))
        assertEquals("Legend", XpLevelCalculator.title(99))
    }

    private fun session(dayOffset: Long, durationMs: Long? = 120_000, endTime: Long? = 1L): PlaySessionEntity {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        // Anchor to start-of-day UTC-ish by using raw day multiples from a fixed epoch day.
        val base = (now / day) * day
        return PlaySessionEntity(
            gameId = "g", sessionId = "s$dayOffset-${durationMs}-${endTime}",
            startTime = base - dayOffset * day, endTime = endTime, durationMs = durationMs,
        )
    }

    @Test
    fun streakEmptyAndTooShortSessions() {
        assertEquals(StreakCalculator.StreakResult(0, 0), StreakCalculator.calculate(emptyList()))
        assertEquals(
            StreakCalculator.StreakResult(0, 0),
            StreakCalculator.calculate(listOf(session(0, durationMs = 1_000))),
        )
        // In-progress session (no duration, no end) never qualifies.
        assertEquals(
            StreakCalculator.StreakResult(0, 0),
            StreakCalculator.calculate(listOf(session(0, durationMs = null, endTime = null))),
        )
    }

    @Test
    fun streakConsecutiveDaysAndGap() {
        val now = System.currentTimeMillis()
        val result = StreakCalculator.calculate(
            listOf(session(0), session(1), session(2), session(4), session(5)),
            now = now,
        )
        assertEquals(3, result.currentStreak)
        assertEquals(3, result.longestStreak)
        // Stale streak: last play 5 days ago -> current 0, longest preserved.
        val stale = StreakCalculator.calculate(listOf(session(5), session(6)), now = now)
        assertEquals(0, stale.currentStreak)
        assertEquals(2, stale.longestStreak)
        // Yesterday still counts as current.
        val yesterday = StreakCalculator.calculate(listOf(session(1)), now = now)
        assertEquals(1, yesterday.currentStreak)
    }

    @Test
    fun streakSurvivesDaylightSavingTransitions() {
        val denver = java.util.TimeZone.getTimeZone("America/Denver")
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(denver)
        try {
            val zone = java.time.ZoneId.of("America/Denver")
            fun noon(m: Int, d: Int) =
                java.time.ZonedDateTime.of(2026, m, d, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
            fun daySession(start: Long) = PlaySessionEntity(
                gameId = "g", sessionId = "s$start",
                startTime = start, endTime = start + 120_000, durationMs = 120_000,
            )
            // Spring forward: 2026-03-08 is a 23-hour day in Denver.
            val spring = StreakCalculator.calculate(
                listOf(daySession(noon(3, 8)), daySession(noon(3, 9))),
                now = noon(3, 9),
            )
            assertEquals(2, spring.currentStreak)
            assertEquals(2, spring.longestStreak)
            // Fall back: 2026-11-01 is a 25-hour day in Denver.
            val fall = StreakCalculator.calculate(
                listOf(daySession(noon(11, 1)), daySession(noon(11, 2))),
                now = noon(11, 2),
            )
            assertEquals(2, fall.currentStreak)
            assertEquals(2, fall.longestStreak)
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun formatPlaytimeAndDuration() {
        assertEquals("0m", formatPlaytime(0))
        assertEquals("0m", formatPlaytime(-5))
        assertEquals("<1m", formatPlaytime(30_000))
        assertEquals("5m", formatPlaytime(300_000))
        assertEquals("1h 30m", formatPlaytime(5_400_000))
        assertEquals("0s", formatDurationMs(0))
        assertEquals("45s", formatDurationMs(45_000))
        assertEquals("2m 5s", formatDurationMs(125_000))
        assertEquals("1h 0m", formatDurationMs(3_600_000))
    }

    @Test
    fun formatRelativeTimeBands() {
        val now = 1_700_000_000_000L
        assertEquals("now", formatRelativeTime(now + 60_000, now))
        assertEquals("just now", formatRelativeTime(now - 10_000, now))
        assertEquals("5m ago", formatRelativeTime(now - 300_000, now))
        assertEquals("3h ago", formatRelativeTime(now - 10_800_000, now))
        assertEquals("2d ago", formatRelativeTime(now - 172_800_000, now))
    }

    @Test
    fun tierColorsKnownAndFallback() {
        assertEquals(Color(0xFFFFD700), tierColor("gold"))
        assertEquals(Color(0xFF9C27B0), tierColor("PLATINUM"))
        assertEquals(Color(0xFF9E9E9E), tierColor("Silver"))
        assertEquals(Color(0xFFCD7F32), tierColor("wood"))
    }
}
