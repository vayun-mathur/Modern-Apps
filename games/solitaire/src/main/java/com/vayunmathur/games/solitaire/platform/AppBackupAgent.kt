package com.vayunmathur.games.solitaire.platform

import com.vayunmathur.library.util.BaseBackupAgent

/** Backup agent covering solitaire stats and achievement data. */
class AppBackupAgent : BaseBackupAgent() {
    // "solitaire_settings" holds the card colour scheme. Omitting it silently reset it on
    // every restore.
    override val prefNames: List<String>
        get() = listOf("solitaire_stats", "solitaire_settings")

    // The shared DataStoreUtils file, holding AchievementsManager unlocks.
    override val datastoreNames: List<String>
        get() = listOf("datastore_default")
}

