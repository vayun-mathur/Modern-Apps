package com.vayunmathur.games.sudoku.platform

import com.vayunmathur.library.util.BaseBackupAgent

/** Backup agent declaring the Sudoku preference and DataStore files restored with the app. */
class AppBackupAgent : BaseBackupAgent() {
    override val prefNames: List<String> get() = listOf("sudoku_stats")

    /**
     * Both files, or a restore silently wipes half the player's history: "settings" holds the
     * answer-mode and highlight-mistakes settings, while the shared DataStoreUtils file holds
     * the AchievementsManager unlocks. Omitting "settings" silently reset both settings on
     * every restore.
     */
    override val datastoreNames: List<String> get() = listOf("settings", "datastore_default")
}
