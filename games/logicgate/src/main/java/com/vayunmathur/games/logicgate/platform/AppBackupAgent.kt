package com.vayunmathur.games.logicgate.platform

import com.vayunmathur.games.logicgate.data.ProgressStore
import com.vayunmathur.library.util.BaseBackupAgent

/**
 * Backs up the stats prefs and the shared DataStore file, which holds the circuit drafts and the
 * sandbox components (`logicgate2_circuits` and `logicgate2_components` are keys inside it, not
 * files of their own).
 */
class AppBackupAgent : BaseBackupAgent() {
    override val prefNames: List<String>
        get() = listOf(ProgressStore.PREFS_NAME)
    override val datastoreNames: List<String>
        get() = listOf("datastore_default")
}
