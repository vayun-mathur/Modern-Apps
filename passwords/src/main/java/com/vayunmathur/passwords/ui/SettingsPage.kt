package com.vayunmathur.passwords.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.passwords.R
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.passwords.domain.ImportSource
import com.vayunmathur.passwords.platform.PasswordsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    backStack: com.vayunmathur.library.util.NavBackStack<com.vayunmathur.passwords.Route>,
    passwordsViewModel: PasswordsViewModel,
) {
    val importing by passwordsViewModel.importing.collectAsState()
    val message by passwordsViewModel.importMessage.collectAsState()

    var selectedSource by remember { mutableStateOf<ImportSource?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            selectedSource?.let { source ->
                passwordsViewModel.importCsv(uri, source)
            }
        }
        selectedSource = null
    }

    DetailScaffold(
        title = stringResource(UiR.string.settings),
        backStack = backStack,
        actions = {
            BackupButtons(
                format = passwordsViewModel.buildBackupFormat(),
            )
        },
    ) {
            Text(stringResource(R.string.import_csv_warning))
            Spacer(Modifier.height(16.dp))

            var dropdownExpanded by remember { mutableStateOf(false) }
            Box {
                Button(
                    onClick = { dropdownExpanded = true },
                    enabled = !importing,
                ) {
                    Text(stringResource(R.string.import_passwords))
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    ImportSource.entries.forEach { source ->
                        DropdownMenuItem(
                            text = { Text(stringResource(source.label)) },
                            onClick = {
                                dropdownExpanded = false
                                selectedSource = source
                                pickLauncher.launch(arrayOf("text/csv", "text/plain", "application/octet-stream", "text/comma-separated-values"))
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (importing) {
                Row(Modifier.fillMaxWidth(), Arrangement.Center) {
                    Text("Loading\u2026")
                }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it)
            }

            KdbxSyncSection()
    }
}
