package com.vayunmathur.games.wordmaker.platform

import com.vayunmathur.library.util.BaseBackupAgent

/** Cloud backup rules for Word Maker DataStore files. */
class AppBackupAgent : BaseBackupAgent() {
    // "settings" is LevelDataStore's preferencesDataStore(name = "settings"); datastore_default is
    // the shared DataStoreUtils file, which is where DailyChallengeStore keeps the daily streak and
    // AchievementsManager keeps unlocks. Omitting it silently reset both on every restore.
    override val datastoreNames: List<String>
        get() = listOf("settings", "datastore_default")
}
