package com.vayunmathur.games.nonogram.platform

import com.vayunmathur.library.util.BaseBackupAgent

/** Backup agent declaring the Nonogram DataStore files restored with the app. */
class AppBackupAgent : BaseBackupAgent() {
    /**
     * Both files, or a restore silently wipes half the player's history: "settings" holds the level
     * and the marks on it, while the shared DataStoreUtils file holds the daily streak and the
     * achievement unlocks.
     */
    override val datastoreNames: List<String> get() = listOf("settings", "datastore_default")
}
