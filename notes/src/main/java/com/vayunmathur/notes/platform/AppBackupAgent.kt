package com.vayunmathur.notes.platform

import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.util.BaseBackupAgent
import com.vayunmathur.notes.data.noteDbConfigs
import java.io.File

/** Backup agent covering the notes database and note images. */
class AppBackupAgent : BaseBackupAgent() {
    override val dbCodec = SqlCipherDbCodec

    override val dbConfigs: List<Pair<String, String>>
        get() = noteDbConfigs(this)

    override val extraFiles: List<File>
        get() = NoteImageStore.backupFiles(this)
}
