package com.vayunmathur.sdk.games

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Client SDK for games to report into the GameHub hub app.
 * No leaderboards — only game registration, achievements, and sessions.
 *
 * All operations are fail-soft: if the hub is not installed, calls are no-ops.
 * Games remain source of truth (Push + Cache model); hub mirrors via Room.
 */
class GameHubClient(
    private val context: Context,
    private val gameId: String
) {
    private val appContext = context.applicationContext

    fun isHubInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(GameHubContract.HUB_PACKAGE, 0)
            true
        } catch (_: Exception) {
            try {
                appContext.packageManager.getPackageInfo(GameHubContract.LEGACY_HUB_PACKAGE, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun registerGame(metadata: GameMetadata): Boolean = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext false
        val resolver = appContext.contentResolver
        val uri = GameHubContract.buildGameUri(metadata.gameId)
        val pkgName = appContext.packageName
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(GameHubContract.Games.GAME_ID, metadata.gameId)
            put(GameHubContract.Games.PACKAGE_NAME, pkgName)
            put(GameHubContract.Games.DISPLAY_NAME, metadata.displayName)
            put(GameHubContract.Games.DESCRIPTION, metadata.description)
            put(GameHubContract.Games.VERSION_NAME, metadata.versionName)
            put(GameHubContract.Games.VERSION_CODE, metadata.versionCode)
            put(GameHubContract.Games.REGISTERED_AT, now)
            put(GameHubContract.Games.LAST_SEEN_AT, now)
        }
        try { resolver.insert(uri, values) != null } catch (_: Exception) { false }
    }

    suspend fun registerAchievements(achievements: List<AchievementDefinition>) = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext
        if (achievements.isEmpty()) return@withContext
        val resolver = appContext.contentResolver
        val uri = GameHubContract.buildAchievementDefsUri(gameId)
        for (def in achievements) {
            val values = ContentValues().apply {
                put(GameHubContract.AchievementDefs.GAME_ID, gameId)
                put(GameHubContract.AchievementDefs.ACHIEVEMENT_ID, def.id)
                put(GameHubContract.AchievementDefs.NAME, def.name)
                put(GameHubContract.AchievementDefs.DESCRIPTION, def.description)
                put(GameHubContract.AchievementDefs.XP_REWARD, def.xpReward)
                put(GameHubContract.AchievementDefs.TARGET_PROGRESS, def.targetProgress)
                put(GameHubContract.AchievementDefs.IS_SECRET, if (def.isSecret) 1 else 0)
                put(GameHubContract.AchievementDefs.TIER, def.tier.name)
                put(GameHubContract.AchievementDefs.ICON_RES_NAME, def.iconResName)
            }
            try { resolver.insert(uri, values) } catch (_: Exception) { }
        }
    }

    suspend fun syncAchievementProgress(statuses: List<AchievementProgress>) = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext
        if (statuses.isEmpty()) return@withContext
        val resolver = appContext.contentResolver
        val uri = GameHubContract.buildAchievementProgressUri(gameId)
        val now = System.currentTimeMillis()
        for (s in statuses) {
            val values = ContentValues().apply {
                put(GameHubContract.AchievementProgressCols.GAME_ID, gameId)
                put(GameHubContract.AchievementProgressCols.ACHIEVEMENT_ID, s.achievementId)
                put(GameHubContract.AchievementProgressCols.PROGRESS, s.progress)
                put(GameHubContract.AchievementProgressCols.IS_UNLOCKED, if (s.isUnlocked) 1 else 0)
                put(GameHubContract.AchievementProgressCols.UNLOCKED_AT, s.unlockedAt)
                put(GameHubContract.AchievementProgressCols.LAST_UPDATED, now)
            }
            try { resolver.insert(uri, values) } catch (_: Exception) { }
        }
    }

    suspend fun unlockAchievement(achievementId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext false
        val resolver = appContext.contentResolver
        val uri = GameHubContract.buildAchievementProgressItemUri(gameId, achievementId)
        val values = ContentValues().apply {
            put(GameHubContract.AchievementProgressCols.GAME_ID, gameId)
            put(GameHubContract.AchievementProgressCols.ACHIEVEMENT_ID, achievementId)
            put(GameHubContract.AchievementProgressCols.PROGRESS, 1)
            put(GameHubContract.AchievementProgressCols.IS_UNLOCKED, 1)
            put(GameHubContract.AchievementProgressCols.UNLOCKED_AT, System.currentTimeMillis())
            put(GameHubContract.AchievementProgressCols.LAST_UPDATED, System.currentTimeMillis())
        }
        try {
            val inserted = resolver.insert(uri, values) != null
            if (!inserted) resolver.update(uri, values, null, null) > 0 else inserted
        } catch (_: Exception) { false }
    }

    suspend fun reportProgress(achievementId: String, progress: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext false
        val resolver = appContext.contentResolver
        val uri = GameHubContract.buildAchievementProgressItemUri(gameId, achievementId)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(GameHubContract.AchievementProgressCols.GAME_ID, gameId)
            put(GameHubContract.AchievementProgressCols.ACHIEVEMENT_ID, achievementId)
            put(GameHubContract.AchievementProgressCols.PROGRESS, progress)
            put(GameHubContract.AchievementProgressCols.LAST_UPDATED, now)
        }
        try {
            val inserted = resolver.insert(uri, values) != null
            if (!inserted) {
                val updateValues = ContentValues().apply {
                    put(GameHubContract.AchievementProgressCols.PROGRESS, progress)
                    put(GameHubContract.AchievementProgressCols.LAST_UPDATED, now)
                }
                resolver.update(uri, updateValues, null, null) > 0
            } else inserted
        } catch (_: Exception) { false }
    }

    suspend fun incrementProgress(achievementId: String, delta: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext false
        val resolver = appContext.contentResolver
        val queryUri = GameHubContract.buildAchievementProgressItemUri(gameId, achievementId)
        var currentProgress = 0
        try {
            resolver.query(queryUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(GameHubContract.AchievementProgressCols.PROGRESS)
                    if (idx >= 0) currentProgress = cursor.getInt(idx)
                }
            }
        } catch (_: Exception) { }
        reportProgress(achievementId, currentProgress + delta)
    }

    suspend fun startSession(): String? = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext null
        val sessionId = UUID.randomUUID().toString()
        val resolver = appContext.contentResolver
        val uri = GameHubContract.buildSessionItemUri(gameId, sessionId)
        val values = ContentValues().apply {
            put(GameHubContract.Sessions.GAME_ID, gameId)
            put(GameHubContract.Sessions.SESSION_ID, sessionId)
            put(GameHubContract.Sessions.START_TIME, System.currentTimeMillis())
        }
        try {
            val result = resolver.insert(uri, values)
            if (result != null) sessionId else null
        } catch (_: Exception) { null }
    }

    suspend fun endSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isHubInstalled()) return@withContext false
        val resolver = appContext.contentResolver
        val uri = GameHubContract.buildSessionItemUri(gameId, sessionId)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(GameHubContract.Sessions.END_TIME, now)
        }
        try { resolver.update(uri, values, null, null) > 0 } catch (_: Exception) { false }
    }

    @Deprecated("Use registerAchievements with AchievementDefinition")
    suspend fun registerAchievementsLegacy(achievements: List<Achievement>) {
        val defs = achievements.map { AchievementDefinition(it.id, it.name, it.description, iconResName = it.iconResName) }
        registerAchievements(defs)
    }

    @Deprecated("Leaderboards removed — no-op")
    suspend fun reportScore(category: String, score: Long) {
        // No-op: leaderboards removed for now.
    }
}
