package com.vayunmathur.games.kart.platform

import com.vayunmathur.library.util.BaseBackupAgent

/** App Backup Agent: a base backup agent implementation for app backup agent. */
class AppBackupAgent : BaseBackupAgent() {
    /**
     * The "settings" file alone: it holds the cup pointer and the lifetime
     * counters. The pairing identity lives in its own `kart_identity` file,
     * deliberately off this list — restoring identity keys onto a different
     * TV would let that TV impersonate this one to an already-paired phone.
     */
    override val datastoreNames: List<String> get() = listOf("settings")
}
