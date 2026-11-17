package com.vayunmathur.games.sudoku.platform

import com.vayunmathur.library.util.BaseBackupAgent

/** Backup agent declaring the Sudoku preference and DataStore files restored with the app. */
class AppBackupAgent : BaseBackupAgent() {
    override val prefNames: List<String> get() = listOf("sudoku_stats")

    // The shared DataStoreUtils file, holding AchievementsManager unlocks.
    override val datastoreNames: List<String> get() = listOf("datastore_default")
}
