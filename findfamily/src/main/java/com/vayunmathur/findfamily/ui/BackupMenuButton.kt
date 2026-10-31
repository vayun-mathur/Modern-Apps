package com.vayunmathur.findfamily.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.domain.FamilyBackup
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.room.SqlCipherDbCodec
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconBackup
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconRestore
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.BackupFormat
import com.vayunmathur.library.util.ZipBackupFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The map overlay's singular backup entry point: an overflow icon that opens a
 * small menu with "Back up" and "Restore".
 *
 * This restores the manual backup UI removed in df2edbb04 ("remove un-needed
 * backup buttons"), adapted to the current transparent bar: previously the same
 * overflow button held two bare backup/restore icon buttons; now it holds two
 * labelled menu items instead. The bar itself stays container-free
 * ([TopAppBarOverlay] draws no background) — this composable only renders the
 * icon and its dropdown.
 *
 * Export writes a user-chosen file via SAF (`ACTION_CREATE_DOCUMENT`) and
 * import reads one via `ACTION_OPEN_DOCUMENT`, using the same [ZipBackupFormat]
 * every other app uses, so files stay interchangeable with older Find Family
 * backups (see [FamilyBackup] for the format contract). The zip is not
 * password-protected — exactly as before — because the database is decrypted
 * into the zip by [SqlCipherDbCodec]; treat the file as sensitive.
 *
 * @param dbPassphrase SQLCipher passphrase for the database export, read off
 *   the composables (see `FindFamilyViewModel.backupPassphrase`).
 * @param menuExpanded whether the backup/restore menu is open; hoisted so the
 *   overlay action wrapping this icon owns the toggle.
 */
@Composable
fun BackupMenuButton(
    dbPassphrase: String,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val format = remember(dbPassphrase) {
        ZipBackupFormat(
            dbConfigs = FamilyBackup.dbConfigs(dbPassphrase),
            datastoreNames = FamilyBackup.datastoreNames(),
            dbCodec = SqlCipherDbCodec,
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(format.mimeType)
    ) { uri ->
        uri?.let { scope.launch(Dispatchers.IO) { runBackupExport(context, format, it) } }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { scope.launch(Dispatchers.IO) { runBackupImport(context, format, it) } }
    }

    IconMoreVert()
    DropdownMenu(menuExpanded, { onMenuExpandedChange(false) }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.backup_menu_backup)) },
            leadingIcon = { IconBackup() },
            onClick = {
                onMenuExpandedChange(false)
                exportLauncher.launch(format.defaultFileName)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.backup_menu_restore)) },
            leadingIcon = { IconRestore() },
            onClick = {
                onMenuExpandedChange(false)
                importLauncher.launch(arrayOf(format.mimeType))
            },
        )
    }
}

/** Long enough for the import message to be read before the process goes away. */
private const val RESTART_DELAY_MS = 1500L

private fun relaunch(context: android.content.Context) {
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        context.startActivity(intent)
    }
    Runtime.getRuntime().exit(0)
}

// Broad catch is deliberate: export/import cross arbitrary content providers and
// the zip codec, and every failure mode is logged and messaged below.
@Suppress("TooGenericExceptionCaught")
private suspend fun runBackupExport(
    context: android.content.Context,
    format: BackupFormat,
    uri: android.net.Uri,
) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { os ->
            format.export(context, null, os)
        }
        withContext(Dispatchers.Main) {
            AppMessages.show(context.getString(UiR.string.backup_export_success))
        }
    } catch (e: Exception) {
        Log.error("BackupMenuButton", "Export FAILED", e)
        withContext(Dispatchers.Main) {
            AppMessages.show(context.getString(UiR.string.backup_export_failed_format, e.message))
        }
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun runBackupImport(
    context: android.content.Context,
    format: BackupFormat,
    uri: android.net.Uri,
) {
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            format.import(context, null, inputStream)
        }
        withContext(Dispatchers.Main) {
            AppMessages.show(context.getString(UiR.string.backup_import_success))
        }
        // DataStore and Room assume exclusive ownership of their files and keep the contents
        // cached, so a live process neither picks up the files the import just replaced nor stops
        // writing the pre-restore state back over them - which silently undid the restore before
        // the user got round to restarting (#548). Give the message a moment, then relaunch.
        delay(RESTART_DELAY_MS)
        withContext(Dispatchers.Main) { relaunch(context) }
    } catch (e: Exception) {
        Log.error("BackupMenuButton", "Import FAILED", e)
        withContext(Dispatchers.Main) {
            AppMessages.show(context.getString(UiR.string.backup_import_failed_format, e.message))
        }
    }
}
