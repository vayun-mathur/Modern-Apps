package com.vayunmathur.passwords.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.passwords.R
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.passwords.util.PasswordsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    backStack: com.vayunmathur.library.util.NavBackStack<com.vayunmathur.passwords.Route>,
    passwordsViewModel: PasswordsViewModel,
    passphrase: String,
) {
    val importing by passwordsViewModel.importing.collectAsState()
    val message by passwordsViewModel.importMessage.collectAsState()

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) passwordsViewModel.importCsv(uri)
    }

    Scaffold(Modifier, {
        TopAppBar(
            { Text(stringResource(R.string.title_settings)) },
            navigationIcon = { IconNavigation(backStack) },
            actions = {
                BackupButtons(
                    dbConfigs = listOf("passwords-db" to passphrase),
                    extraFiles = emptyList(),
                )
            },
        )
    }) { paddingValues ->
        Column(Modifier
            .padding(paddingValues)
            .fillMaxSize()
            .padding(16.dp), Arrangement.Top
        ) {

            Text(stringResource(R.string.import_csv_warning))
            Spacer(Modifier.height(16.dp))

            Button(onClick = {
                pickLauncher.launch(arrayOf("text/csv", "text/plain", "application/octet-stream", "text/comma-separated-values"))
            }, enabled = !importing) {
                Text(stringResource(R.string.import_bitwarden_csv))
            }

            Spacer(Modifier.height(16.dp))

            if (importing) {
                Row(Modifier.fillMaxWidth(), Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it)
            }
        }
    }
}
