package com.vayunmathur.games.unblockjam.platform

import com.vayunmathur.library.util.BaseBackupAgent

/** Cloud backup rules for Unblock Jam preferences and DataStore. */
class AppBackupAgent : BaseBackupAgent() {
    override val prefNames: List<String>
        get() = listOf("level_stats", "daily_stats")

    // The shared DataStoreUtils file, holding AchievementsManager unlocks and the
    // DailyChallengeStore("unblockjam_daily") streak.
    override val datastoreNames: List<String>
        get() = listOf("datastore_default")
}
