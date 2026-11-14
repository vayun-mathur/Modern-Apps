package com.vayunmathur.weather.platform

import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.weather.data.weatherDbConfigs

/** App Backup Agent: a base backup agent implementation for app backup agent. */
class AppBackupAgent : BaseBackupAgent() {
    override val dbCodec = SqlCipherDbCodec

    override val dbConfigs: List<Pair<String, String>>
        get() = weatherDbConfigs(this)
}
