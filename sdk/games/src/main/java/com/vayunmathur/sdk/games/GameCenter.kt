package com.vayunmathur.sdk.games

/**
 * Legacy wrapper preserving old GameCenter API surface.
 * Achievements delegate to [GameHubClient]; scores are no-ops since leaderboards removed.
 * New code should use [GameHubClient] directly.
 */
@Suppress("DEPRECATION")
class GameCenterLegacy(private val context: android.content.Context, private val gameId: String) {

    private val client = GameHubClient(context, gameId)

    suspend fun registerAchievements(achievements: List<Achievement>) {
        client.registerAchievements(
            achievements.map {
                AchievementDefinition(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    iconResName = it.iconResName
                )
            }
        )
    }

    suspend fun unlockAchievement(achievementId: String): Boolean =
        client.unlockAchievement(achievementId)

    suspend fun getAchievements(): List<AchievementStatus> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!client.isHubInstalled()) return@withContext emptyList()
            val authority = "com.vayunmathur.games.provider"
            val baseUri = android.net.Uri.parse("content://$authority")
            val resolver = context.contentResolver
            val uri = android.net.Uri.withAppendedPath(baseUri, "achievements/$gameId")
            val results = mutableListOf<AchievementStatus>()
            try {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val achievement = Achievement(
                            id = cursor.getString(cursor.getColumnIndexOrThrow("achievement_id")),
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                            iconResName = cursor.getString(cursor.getColumnIndexOrThrow("icon_res_name"))
                        )
                        val unlocked = cursor.getInt(cursor.getColumnIndexOrThrow("unlocked")) == 1
                        val unlockedAt = if (unlocked) cursor.getLong(cursor.getColumnIndexOrThrow("unlocked_at")) else null
                        results.add(AchievementStatus(achievement, unlocked, unlockedAt))
                    }
                }
            } catch (_: Exception) { }
            results
        }

    // No-op: leaderboards removed
    suspend fun reportScore(category: String, score: Long) { }

    // Always empty: leaderboards removed
    suspend fun getScores(category: String): List<ScoreEntry> = emptyList()

    fun isHubInstalled(): Boolean = client.isHubInstalled()
}

data class ScoreEntry(
    val gameId: String,
    val category: String,
    val score: Long,
    val timestamp: Long
)

typealias GameCenter = GameCenterLegacy
