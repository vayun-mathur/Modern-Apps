package com.vayunmathur.games.hub.provider

import android.content.ContentValues
import android.net.Uri
import com.vayunmathur.games.hub.data.GamesHubRepository
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.sdk.games.GameHubContract

internal suspend fun GamesHubProvider.updateProgressItem(
    uri: Uri,
    v: ContentValues,
    repo: GamesHubRepository,
): Int {
    val gameId = progressGameId(uri, v) ?: return 0
    val achId = progressAchievementId(uri, v) ?: return 0
    val isUnlocked = progressUnlocked(v)
    val unlockedAt = v.getAsLong("unlocked_at")
        ?: v.getAsLong(GameHubContract.AchievementProgressCols.UNLOCKED_AT)
    val progressVal = progressValue(v)
    val existing = repo.achievements.getProgress(gameId, achId)
    val merged = mergeUpdateProgress(existing, gameId, achId, progressVal, isUnlocked, unlockedAt)
    repo.achievements.upsertProgress(merged)
    val wasUnlockedBefore = existing?.isUnlocked ?: false
    if (merged.isUnlocked && !wasUnlockedBefore) {
        val def = repo.achievements.getAchievementDef(gameId, achId)
        repo.activity.upsertActivity(
            ActivityEventEntity(
                type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED,
                gameId = gameId,
                title = def?.name ?: achId,
                description = def?.description
            )
        )
    }
    return 1
}

internal fun progressGameId(uri: Uri, v: ContentValues): String? =
    uri.pathSegments.getOrNull(1)
        ?: v.getAsString(GameHubContract.AchievementProgressCols.GAME_ID)
        ?: v.getAsString("game_id")

internal fun progressAchievementId(uri: Uri, v: ContentValues): String? =
    uri.pathSegments.lastOrNull()?.takeIf { it != "progress" }
        ?: v.getAsString(GameHubContract.AchievementProgressCols.ACHIEVEMENT_ID)
        ?: v.getAsString("achievement_id")

internal fun progressValue(v: ContentValues): Int =
    v.getAsInteger(GameHubContract.AchievementProgressCols.PROGRESS)
        ?: v.getAsInteger("progress") ?: 0

internal fun mergeUpdateProgress(
    existing: AchievementProgressEntity?,
    gameId: String,
    achId: String,
    progressVal: Int,
    isUnlocked: Boolean,
    unlockedAt: Long?,
): AchievementProgressEntity {
    if (existing == null) {
        return AchievementProgressEntity(
            gameId = gameId,
            achievementId = achId,
            progress = progressVal,
            isUnlocked = isUnlocked,
            unlockedAt = unlockedAt ?: if (isUnlocked) System.currentTimeMillis() else null,
            lastUpdated = System.currentTimeMillis()
        )
    }
    return existing.copy(
        progress = maxOf(existing.progress, progressVal),
        isUnlocked = existing.isUnlocked || isUnlocked,
        unlockedAt = existing.unlockedAt ?: unlockedAt
            ?: if (isUnlocked) System.currentTimeMillis() else null,
        lastUpdated = System.currentTimeMillis()
    )
}

internal suspend fun GamesHubProvider.updateSession(
    uri: Uri,
    v: ContentValues,
    repo: GamesHubRepository,
    database: com.vayunmathur.games.hub.data.GamesHubDatabase,
): Int {
    val sessionId = uri.pathSegments.lastOrNull() ?: return 0
    val existing = repo.sessions.getSessionById(sessionId) ?: return 0
    // Idempotent end: a repeated update (game-side retry, double lifecycle
    // callback, process-death replay) must not add playtime or log completion
    // twice. An already-ended session stays ended.
    if (existing.endTime != null) return 1
    val endTime = v.getAsLong(GameHubContract.Sessions.END_TIME)
        ?: v.getAsLong("end_time") ?: System.currentTimeMillis()
    val duration = (endTime - existing.startTime).coerceAtLeast(0L)
    repo.sessions.endSession(sessionId, endTime, duration)
    database.gameDao().addPlaytime(existing.gameId, duration)
    repo.activity.upsertActivity(
        ActivityEventEntity(
            type = ActivityEventEntity.TYPE_SESSION_COMPLETED,
            gameId = existing.gameId,
            title = "Played ${existing.gameId}",
            description = "Session ${duration / GamesHubProvider.MILLIS_PER_SECOND}s"
        )
    )
    return 1
}
