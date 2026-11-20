package com.vayunmathur.backup.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.backup.R
import com.vayunmathur.backup.data.BackendType
import com.vayunmathur.backup.data.BackupSettings
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

/** Human-readable summary of the currently selected destination. */
fun BackupSettings.destinationLabel(context: Context): String = when (backendType) {
    BackendType.NONE -> context.getString(R.string.destination_none)
    BackendType.SAF -> context.getString(
        R.string.destination_folder,
        safTreeUri?.substringAfterLast(':')?.substringAfterLast('/')
            ?: context.getString(R.string.destination_selected),
    )
    BackendType.WEBDAV -> context.getString(R.string.destination_webdav, webdavUrl)
}

/** Lets the user pick a SAF folder or configure a WebDAV/Nextcloud remote. */
@Composable
fun BackendSection(
    settings: BackupSettings,
    onPickFolder: () -> Unit,
    onSetWebDav: (url: String, user: String, password: String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.backend_title), style = MaterialTheme.typography.titleMedium)
            Text(settings.destinationLabel(LocalContext.current), style = MaterialTheme.typography.bodyMedium)

            Button(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.backend_choose_folder))
            }

            HorizontalDivider()
            Text(stringResource(R.string.backend_or_webdav), style = MaterialTheme.typography.labelLarge)

            var url by remember(settings.webdavUrl) { mutableStateOf(settings.webdavUrl) }
            var user by remember(settings.webdavUser) { mutableStateOf(settings.webdavUser) }
            var password by remember { mutableStateOf("") }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.backend_webdav_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text(stringResource(R.string.backend_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.backend_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSetWebDav(url, user, password) },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.backend_save_webdav)) }
        }
    }
}
