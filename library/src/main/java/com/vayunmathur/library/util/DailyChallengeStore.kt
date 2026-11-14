package com.vayunmathur.library.util

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/**
 * Day/streak bookkeeping for daily challenges, namespaced by [keyPrefix] so several games can
 * share the single [DataStoreUtils] instance.
 */
class DailyChallengeStore(context: Context, private val keyPrefix: String) {

    /** Current and best consecutive-day completion counts. */
    data class Streak(val current: Long, val best: Long)

    private val store = DataStoreUtils.getInstance(context)

    private val lastDayKey = "${keyPrefix}_last_completed_day"
    private val currentStreakKey = "${keyPrefix}_current_streak"
    private val bestStreakKey = "${keyPrefix}_best_streak"

    fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    suspend fun lastCompletedDay(): Long = store.getLongAwait(lastDayKey) ?: NO_DAY

    suspend fun bestStreak(): Long = store.getLongAwait(bestStreakKey) ?: 0L

    suspend fun recordDayCompleted(day: Long): Streak {
        val last = store.getLongAwait(lastDayKey) ?: NO_DAY
        val stored = store.getLongAwait(currentStreakKey) ?: 0L
        val current = when {
            last == day -> stored.coerceAtLeast(1L)
            last == day - 1 -> stored + 1
            else -> 1L
        }
        store.setLong(lastDayKey, day)
        store.setLong(currentStreakKey, current)
        store.setLongIfGreater(bestStreakKey, current)
        val best = store.getLongAwait(bestStreakKey) ?: current
        return Streak(current, best)
    }

    /** 0 unless the last completed day is today or yesterday, so a lapsed streak reads as broken. */
    val currentStreak: Flow<Long> =
        combine(store.longFlow(lastDayKey, NO_DAY), store.longFlow(currentStreakKey, 0L)) { last, streak ->
            val today = todayEpochDay()
            if (last == today || last == today - 1) streak else 0L
        }

    val bestStreakFlow: Flow<Long> = store.longFlow(bestStreakKey, 0L)

    val lastCompletedDayFlow: Flow<Long> = store.longFlow(lastDayKey, NO_DAY)

    /** Sentinel epoch-day meaning no day has been completed yet. */
    companion object {
        const val NO_DAY = Long.MIN_VALUE
    }
}
