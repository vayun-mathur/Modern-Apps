package com.vayunmathur.library.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val achievementsJson = Json { ignoreUnknownKeys = true }

/**
 * Tracks achievement unlocks and progress in the app-wide DataStore.
 *
 * [keyPrefix] namespaces the stored keys. The default empty prefix is the app-level tier — the one the
 * Games Hub mirrors — so every existing game keeps writing exactly the keys it always has. A game with
 * multiple saves can additionally construct one manager per save with a prefix, giving each its own
 * independent progress without touching the app-level totals.
 */
abstract class AchievementsManager(
    val context: Context,
    jsonContent: String,
    private val keyPrefix: String = "",
) {
    protected val ds = DataStoreUtils.getInstance(context)
    val achievements: List<Achievement> =
        achievementsJson.decodeFromString(ListSerializer(Achievement.serializer()), jsonContent)

    private val unlockedKey get() = "${keyPrefix}achievements_unlocked"
    private fun progressKey(id: String) = "${keyPrefix}achievement_progress_$id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Unlocks not yet shown, oldest first. Unlocks often land together — a win can complete "first win"
     * and "ten wins" in one move — and each deserves its own toast, so they wait their turn here instead
     * of overwriting one another.
     */
    private val pending = ArrayDeque<Achievement>()
    private val _newAchievement = MutableStateFlow<Achievement?>(null)

    /** The unlock to show now: the oldest one not yet dismissed, or null when none is waiting. */
    val newAchievement: StateFlow<Achievement?> = _newAchievement.asStateFlow()

    abstract fun checkExistingAchievements()

    fun onAchievementUnlocked(id: String) {
        val achievement = achievements.find { it.id == id } ?: return
        scope.launch {
            if (ds.addStringToSetIfAbsent(unlockedKey, id)) enqueue(achievement)
        }
    }

    fun onProgressUpdated(id: String, progress: Int) {
        val achievement = achievements.find { it.id == id } ?: return
        scope.launch {
            if (ds.setLongIfGreater(progressKey(id), progress.toLong()) &&
                progress >= achievement.targetProgress
            ) {
                onAchievementUnlocked(id)
            }
        }
    }

    fun getAchievementStatuses(): Flow<List<AchievementStatus>> {
        val unlockedFlow = ds.stringSetFlow(unlockedKey)
        val progressFlows = achievements.map { ds.longFlow(progressKey(it.id), 0L) }
        return combine(unlockedFlow, combine(progressFlows) { it }) { unlocked, progresses ->
            achievements.mapIndexed { index, achievement ->
                AchievementStatus(
                    achievement = achievement,
                    progress = progresses[index].toInt(),
                    isUnlocked = unlocked.contains(achievement.id)
                )
            }
        }
    }

    /** The current toast is done: drop it and move the next waiting unlock, if any, into [newAchievement]. */
    fun dismissNotification() {
        synchronized(pending) {
            pending.removeFirstOrNull()
            _newAchievement.value = pending.firstOrNull()
        }
    }

    private fun enqueue(achievement: Achievement) {
        synchronized(pending) {
            pending.addLast(achievement)
            _newAchievement.value = pending.first()
        }
    }

    /** Forget everything stored under [keyPrefix]. Used when the save it belongs to is deleted. */
    suspend fun forgetAll() {
        ds.removeKeys(listOf(unlockedKey) + achievements.map { progressKey(it.id) })
    }
}
