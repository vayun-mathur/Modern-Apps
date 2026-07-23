package com.vayunmathur.games.hub.util

import com.vayunmathur.games.hub.data.DB_NAME
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.library.util.DatabaseHelper
import java.io.File

class AppBackupAgent : BaseBackupAgent() {
    override val dbCodec = SqlCipherDbCodec

    override val dbConfigs: List<Pair<String, String>>
        get() {
            return try {
                val pass = DatabaseHelper(this).getPassphrase()
                listOf(DB_NAME to pass)
            } catch (_: Exception) {
                emptyList()
            }
        }

    override val datastoreNames: List<String>
        get() = listOf("datastore_default")

    override val extraFiles: List<File>
        get() = emptyList()
}
