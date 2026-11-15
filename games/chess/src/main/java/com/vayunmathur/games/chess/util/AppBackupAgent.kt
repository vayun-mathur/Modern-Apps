package com.vayunmathur.games.chess.util

import com.vayunmathur.library.util.BaseBackupAgent

/** App Backup Agent: a base backup agent implementation for app backup agent. */
class AppBackupAgent : BaseBackupAgent() {
    override val datastoreNames: List<String>
        get() = listOf("datastore_default")
}
