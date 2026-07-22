package com.vayunmathur.games.logicgate.util

import com.vayunmathur.library.util.BaseBackupAgent

class AppBackupAgent : BaseBackupAgent() {
    override val prefNames: List<String>
        get() = listOf("logicgate_stats")
    override val datastoreNames: List<String>
        get() = listOf("logic_gate_datastore")
}
