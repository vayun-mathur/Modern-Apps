package com.vayunmathur.games.arrows.platform

import com.vayunmathur.library.util.BaseBackupAgent

/** Backup agent declaring the Arrows DataStore files restored with the app. */
class AppBackupAgent : BaseBackupAgent() {
    /**
     * Both files, or a restore silently wipes half the player's history: "settings" holds the level
     * and the board in progress, while the shared DataStoreUtils file holds the daily streak and the
     * achievement unlocks.
     */
    override val datastoreNames: List<String> get() = listOf("settings", "datastore_default")
}
