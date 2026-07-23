package com.vayunmathur.library.util

import android.content.Context
import com.vayunmathur.sdk.games.AchievementDefinition
import com.vayunmathur.sdk.games.AchievementProgress
import com.vayunmathur.sdk.games.AchievementTier
import com.vayunmathur.sdk.games.GameHubClient
import com.vayunmathur.sdk.games.GameMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Bridges legacy [AchievementsManager] into [GameHubClient].
 * Push+Cache: games remain source of truth, hub mirrors via Room.
 * No leaderboards — only achievements + registration + sessions handled elsewhere.
 */
object GameHubReporter {

    fun bind(
        context: Context,
        gameId: String,
        legacyManager: AchievementsManager,
        hubClient: GameHubClient,
        gameMetadata: GameMetadata = GameMetadata(gameId, displayName = gameId.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() })
    ): ReporterHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        scope.launch {
            try {
                hubClient.registerGame(gameMetadata)
                val defs = legacyManager.achievements.map { legacy ->
                    AchievementDefinition(
                        id = legacy.id,
                        name = legacy.name,
                        description = legacy.description,
                        xpReward = xpForAchievement(legacy.id),
                        targetProgress = legacy.targetProgress.coerceAtLeast(1),
                        isSecret = legacy.isSecret,
                        tier = tierForAchievement(legacy.id)
                    )
                }
                hubClient.registerAchievements(defs)

                val statuses = try {
                    legacyManager.getAchievementStatuses().first()
                } catch (_: Exception) {
                    emptyList()
                }
                val progressList = statuses.map { s ->
                    AchievementProgress(
                        achievementId = s.achievement.id,
                        progress = s.progress,
                        isUnlocked = s.isUnlocked,
                        unlockedAt = if (s.isUnlocked) System.currentTimeMillis() else null
                    )
                }
                if (progressList.isNotEmpty()) hubClient.syncAchievementProgress(progressList)
            } catch (_: Exception) { }
        }

        scope.launch {
            try {
                legacyManager.newAchievement.collectLatest { achievement ->
                    if (achievement != null) hubClient.unlockAchievement(achievement.id)
                }
            } catch (_: Exception) { }
        }

        scope.launch {
            try {
                legacyManager.getAchievementStatuses().collectLatest { statuses ->
                    val progressList = statuses.map { s ->
                        AchievementProgress(
                            achievementId = s.achievement.id,
                            progress = s.progress,
                            isUnlocked = s.isUnlocked,
                            unlockedAt = if (s.isUnlocked) System.currentTimeMillis() else null
                        )
                    }
                    hubClient.syncAchievementProgress(progressList)
                }
            } catch (_: Exception) { }
        }

        return ReporterHandle(job)
    }

    class ReporterHandle(private val job: Job) {
        fun cancel() { job.cancel() }
    }

    private fun xpForAchievement(id: String): Int = when {
        "win_50" in id || "master" in id -> 100
        "win_10" in id || "enthusiast" in id || "100" in id || "500" in id -> 50
        "first_win" in id || "first_mate" in id || "level_1_done" in id -> 50
        else -> 25
    }

    private fun tierForAchievement(id: String): AchievementTier = when {
        "win_50" in id || "master" in id || "level_500" in id -> AchievementTier.PLATINUM
        "win_10" in id || "win_vs_ai_hard" in id || "level_100" in id -> AchievementTier.GOLD
        id.startsWith("win_") || "first" in id || "level_1_done" in id -> AchievementTier.SILVER
        else -> AchievementTier.BRONZE
    }
}

class GameHubSessionReporter(private val client: GameHubClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentSessionId: String? = null

    fun start() {
        scope.launch {
            try {
                if (currentSessionId == null) currentSessionId = client.startSession()
            } catch (_: Exception) { }
        }
    }

    fun end() {
        val sid = currentSessionId ?: return
        currentSessionId = null
        scope.launch { try { client.endSession(sid) } catch (_: Exception) { } }
    }
}
