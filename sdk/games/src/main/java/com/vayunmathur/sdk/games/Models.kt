package com.vayunmathur.sdk.games

/**
 * Achievement tier determines XP reward baseline.
 * Matches Play Games parity design: BRONZE=10, SILVER=25, GOLD=50, PLATINUM=100.
 */
@Suppress("MagicNumber")
enum class AchievementTier(val defaultXp: Int) {
    BRONZE(10),
    SILVER(25),
    GOLD(50),
    PLATINUM(100),
}

/**
 * Metadata about a registered game. Pushed via [GameHubClient.registerGame].
 */
data class GameMetadata(
    val gameId: String,
    val displayName: String,
    val description: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null
)

/**
 * Definition of an achievement, as declared by the game.
 */
data class AchievementDefinition(
    val id: String,
    val name: String,
    val description: String,
    val xpReward: Int = 25,
    val targetProgress: Int = 1,
    val isSecret: Boolean = false,
    val tier: AchievementTier = AchievementTier.BRONZE,
    val iconResName: String? = null
)

/**
 * Current progress for an achievement, pushed by the game as source of truth.
 */
data class AchievementProgress(
    val achievementId: String,
    val progress: Int = 0,
    val isUnlocked: Boolean = false,
    val unlockedAt: Long? = null
)

/**
 * A game's daily-puzzle streak, pushed by the game as source of truth.
 *
 * [lastCompletedDay] is an epoch day, so the hub can tell a live streak from a lapsed one
 * without depending on when the row was written.
 */
data class DailyStreak(
    val currentStreak: Int,
    val longestStreak: Int,
    val lastCompletedDay: Long,
)

// ──────── Legacy models — kept for backward compatibility ────────

/**
 * @deprecated Use [AchievementDefinition] instead. Kept for backward compat.
 */
@Deprecated("Use AchievementDefinition")
data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val iconResName: String? = null
)

/**
 * @deprecated.
 */
@Deprecated("Use AchievementProgress")
data class AchievementStatus(
    val achievement: Achievement,
    val unlocked: Boolean,
    val unlockedAt: Long?
)

/** Thrown when the Games Hub app is not installed on this device. */
class GameHubNotInstalledException :
    Exception("Games Hub app is not installed on this device")
