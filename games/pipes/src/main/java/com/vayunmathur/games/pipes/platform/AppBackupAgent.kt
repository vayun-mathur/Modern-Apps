package com.vayunmathur.games.pipes.platform

import com.vayunmathur.library.util.BaseBackupAgent

class AppBackupAgent : BaseBackupAgent() {
    override val prefNames: List<String>
        get() = listOf("level_stats", "daily_stats", "pipes_progress")

    // The shared DataStoreUtils file, holding AchievementsManager unlocks and the
    // DailyChallengeStore("pipes_daily") streak.
    override val datastoreNames: List<String>
        get() = listOf("datastore_default")
}
