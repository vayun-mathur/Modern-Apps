package com.vayunmathur.flashcards.util

import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.flashcards.data.flashcardsDbConfigs
import java.io.File

/** Backup agent that backs up the encrypted flashcards database and its DataStore settings. */
class AppBackupAgent : BaseBackupAgent() {
    override val dbCodec = SqlCipherDbCodec

    override val dbConfigs: List<Pair<String, String>>
        get() = flashcardsDbConfigs(this)

    // The shared DataStoreUtils file, holding app settings. res/xml/data_extraction_rules.xml
    // already lists it; the agent has to repeat it because it, not the XML, drives cloud backup.
    override val datastoreNames: List<String>
        get() = listOf("datastore_default")

    override val extraFiles: List<File>
        get() = emptyList()
}
