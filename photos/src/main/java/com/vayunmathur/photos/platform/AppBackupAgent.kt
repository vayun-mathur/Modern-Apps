package com.vayunmathur.photos.platform

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
            return listOf("vault-db" to pass)
        }

    // The shared DataStoreUtils file, holding the grid column count and model-version markers.
    // res/xml/data_extraction_rules.xml already lists it; the agent has to repeat it because it,
    // not the XML, drives cloud backup.
    override val datastoreNames: List<String>
        get() = listOf("datastore_default")

    override val extraFiles: List<File>
        get() = listOf(File(filesDir, "secure_vault"))
}
