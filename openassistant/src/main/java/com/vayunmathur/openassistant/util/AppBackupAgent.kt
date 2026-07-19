package com.vayunmathur.openassistant.util

import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.library.util.DatabaseHelper
import java.io.File

class AppBackupAgent : BaseBackupAgent() {
    override val dbCodec = SqlCipherDbCodec

    override val dbConfigs: List<Pair<String, String>>
        get() = runCatching {
            val helper = DatabaseHelper(this)
            if (helper.isKeyGenerated()) listOf("passwords-db" to helper.getPassphrase())
            else emptyList()
        }.getOrDefault(emptyList())

    override val extraFiles: List<File> get() = emptyList()
}
