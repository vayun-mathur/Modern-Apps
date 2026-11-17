package com.vayunmathur.sdk.games

import android.net.Uri
import androidx.core.net.toUri

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

    val BASE_URI: Uri = "content://$AUTHORITY".toUri()
    val LEGACY_BASE_URI: Uri = "content://$LEGACY_AUTHORITY".toUri()

    /** Column names for the Games Hub games table. */
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

    /** Column names for the Games Hub achievement-definitions table. */
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

    /** Column names for the Games Hub achievement-progress table. */
    object AchievementProgressCols {
        const val GAME_ID = "game_id"
        const val ACHIEVEMENT_ID = "achievement_id"
        const val PROGRESS = "progress"
        const val IS_UNLOCKED = "is_unlocked"
        const val UNLOCKED_AT = "unlocked_at"
        const val LAST_UPDATED = "last_updated"
    }

    /** Column names for the Games Hub play-sessions table. */
    object Sessions {
        const val GAME_ID = "game_id"
        const val SESSION_ID = "session_id"
        const val START_TIME = "start_time"
        const val END_TIME = "end_time"
        const val DURATION_MS = "duration_ms"
    }

    /**
     * A game's daily-puzzle streak. One row per game, not per day: the game's own
     * `DailyChallengeStore` is the source of truth and only keeps current/best/last-day.
     */
    object Streaks {
        const val GAME_ID = "game_id"
        const val CURRENT_STREAK = "current_streak"
        const val LONGEST_STREAK = "longest_streak"
        const val LAST_COMPLETED_DAY = "last_completed_day"
        const val LAST_UPDATED = "last_updated"
    }

    // Legacy columns (from old SDK)
    /** Legacy column names kept for the older Games Hub provider schema. */
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

    fun buildStreakUri(gameId: String): Uri =
        BASE_URI.buildUpon().appendPath("streaks").appendPath(gameId).build()

    // Legacy URI helpers
    fun buildLegacyAchievementsUri(gameId: String): Uri =
        BASE_URI.buildUpon().appendPath("achievements").appendPath(gameId).build()

    fun buildLegacyAchievementItemUri(gameId: String, achievementId: String): Uri =
        BASE_URI.buildUpon().appendPath("achievements").appendPath(gameId).appendPath(achievementId).build()
}
