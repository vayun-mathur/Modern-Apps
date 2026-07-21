package com.vayunmathur.findfamily.util

import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.library.util.DatabaseHelper
import java.io.File

class AppBackupAgent : BaseBackupAgent() {
    override val dbCodec = SqlCipherDbCodec

    override val dbConfigs: List<Pair<String, String>>
        get() {
            val pass = DatabaseHelper(this).getPassphrase()
            return listOf("passwords-db" to pass)
        }

    // Back up the app's DataStore (userid + e2ee keypair live here) so a new
    // device restore keeps a stable identity instead of regenerating one.
    override val datastoreNames: List<String>
        get() = listOf("datastore_default")

    override val extraFiles: List<File>
        get() = emptyList()
}
