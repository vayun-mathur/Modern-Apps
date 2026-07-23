package com.vayunmathur.sdk.games

import android.net.Uri

/**
 * Contract for the Games Hub ContentProvider.
 * No leaderboards — only games, achievements, sessions.
 *
 * Authority: com.vayunmathur.games.hub.provider
 * Legacy authority (also served by hub): com.vayunmathur.games.provider
 */
object GameHubContract {

    const val AUTHORITY = "com.vayunmathur.games.hub.provider"
    const val LEGACY_AUTHORITY = "com.vayunmathur.games.provider"
    const val HUB_PACKAGE = "com.vayunmathur.games.hub"
    const val LEGACY_HUB_PACKAGE = "com.vayunmathur.games"

    val BASE_URI: Uri = Uri.parse("content://$AUTHORITY")
    val LEGACY_BASE_URI: Uri = Uri.parse("content://$LEGACY_AUTHORITY")

    object Games {
        const val GAME_ID = "game_id"
        const val PACKAGE_NAME = "package_name"
        const val DISPLAY_NAME = "display_name"
        const val DESCRIPTION = "description"
        const val VERSION_NAME = "version_name"
        const val VERSION_CODE = "version_code"
        const val REGISTERED_AT = "registered_at"
        const val LAST_SEEN_AT = "last_seen_at"
    }

    object AchievementDefs {
        const val GAME_ID = "game_id"
        const val ACHIEVEMENT_ID = "achievement_id"
        const val NAME = "name"
        const val DESCRIPTION = "description"
        const val XP_REWARD = "xp_reward"
        const val TARGET_PROGRESS = "target_progress"
        const val IS_SECRET = "is_secret"
        const val TIER = "tier"
        const val ICON_RES_NAME = "icon_res_name"
    }

    object AchievementProgressCols {
        const val GAME_ID = "game_id"
        const val ACHIEVEMENT_ID = "achievement_id"
        const val PROGRESS = "progress"
        const val IS_UNLOCKED = "is_unlocked"
        const val UNLOCKED_AT = "unlocked_at"
        const val LAST_UPDATED = "last_updated"
    }

    object Sessions {
        const val GAME_ID = "game_id"
        const val SESSION_ID = "session_id"
        const val START_TIME = "start_time"
        const val END_TIME = "end_time"
        const val DURATION_MS = "duration_ms"
    }

    // Legacy columns (from old SDK)
    object Legacy {
        const val ACHIEVEMENT_ID = "achievement_id"
        const val NAME = "name"
        const val DESCRIPTION = "description"
        const val ICON_RES_NAME = "icon_res_name"
        const val UNLOCKED = "unlocked"
        const val UNLOCKED_AT = "unlocked_at"
        const val GAME_ID = "game_id"
    }

    // ----- URI builders -----
    fun buildGamesUri(): Uri = BASE_URI.buildUpon().appendPath("games").build()

    fun buildGameUri(gameId: String): Uri =
        BASE_URI.buildUpon().appendPath("games").appendPath(gameId).build()

    fun buildAchievementDefsUri(gameId: String): Uri =
        BASE_URI.buildUpon().appendPath("achievements").appendPath(gameId).appendPath("defs").build()

    fun buildAchievementProgressUri(gameId: String): Uri =
        BASE_URI.buildUpon().appendPath("achievements").appendPath(gameId).appendPath("progress").build()

    fun buildAchievementProgressItemUri(gameId: String, achievementId: String): Uri =
        BASE_URI.buildUpon().appendPath("achievements").appendPath(gameId)
            .appendPath("progress").appendPath(achievementId).build()

    fun buildSessionsUri(gameId: String): Uri =
        BASE_URI.buildUpon().appendPath("sessions").appendPath(gameId).build()

    fun buildSessionItemUri(gameId: String, sessionId: String): Uri =
        BASE_URI.buildUpon().appendPath("sessions").appendPath(gameId).appendPath(sessionId).build()

    // Legacy URI helpers
    fun buildLegacyAchievementsUri(gameId: String): Uri =
        BASE_URI.buildUpon().appendPath("achievements").appendPath(gameId).build()

    fun buildLegacyAchievementItemUri(gameId: String, achievementId: String): Uri =
        BASE_URI.buildUpon().appendPath("achievements").appendPath(gameId).appendPath(achievementId).build()
}
