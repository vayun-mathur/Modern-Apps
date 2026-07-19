package com.vayunmathur.notes.util

import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.notes.data.noteDbConfigs
import java.io.File

class AppBackupAgent : BaseBackupAgent() {
    override val dbCodec = SqlCipherDbCodec

    override val dbConfigs: List<Pair<String, String>>
        get() = noteDbConfigs(this)

    override val extraFiles: List<File>
        get() = emptyList()
}
