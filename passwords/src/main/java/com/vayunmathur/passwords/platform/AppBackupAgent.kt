package com.vayunmathur.passwords.platform

import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.library.util.DatabaseHelper
import java.io.File

/** App Backup Agent: a base backup agent implementation for app backup agent. */
class AppBackupAgent : BaseBackupAgent() {
    override val dbCodec = SqlCipherDbCodec

    override val dbConfigs: List<Pair<String, String>>
        get() {
            val pass = DatabaseHelper(this).getPassphrase()
            return listOf("passwords-db" to pass)
        }

    override val extraFiles: List<File>
        get() = emptyList()
}
