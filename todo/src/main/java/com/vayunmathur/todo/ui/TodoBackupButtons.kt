package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.todo.data.todoDbConfigs

/** Binds the repository-backed config to the shared backup buttons. */
@Composable
fun TodoBackupButtons() {
    val context = LocalContext.current
    BackupButtons(
        dbConfigs = remember { todoDbConfigs(context) },
        dbCodec = SqlCipherDbCodec,
        extraFiles = emptyList(),
    )
}
