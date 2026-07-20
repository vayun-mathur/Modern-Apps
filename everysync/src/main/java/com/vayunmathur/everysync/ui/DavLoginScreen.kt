package com.vayunmathur.everysync.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.library.util.NavBackStack

@Composable
fun DavLoginScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel, providerId: String) {
    val context = LocalContext.current
    val provider = ProviderRegistry.get(providerId)
    val isICloud = providerId == "icloud"
    // iCloud uses fixed hosts (handled by the provider), so the base URL is the
    // preset and never shown; generic DAV servers need the user to enter it.
    var baseUrl by remember { mutableStateOf(provider?.davPresetUrl ?: "") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text(stringResource(R.string.dav_login_title, provider?.displayName ?: "")) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (isICloud) {
                    Text(stringResource(R.string.icloud_app_password_steps))
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, "https://account.apple.com".toUri())
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) { Text(stringResource(R.string.icloud_create_password)) }
                } else {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.dav_base_url)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(if (isICloud) R.string.apple_id_email else R.string.dav_username)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(if (isICloud) R.string.app_specific_password else R.string.dav_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.isNotBlank() && baseUrl.isNotBlank(),
                onClick = {
                    viewModel.davLogin(providerId, baseUrl.trim(), username.trim(), password) { backStack.reset(Route.Accounts) }
                },
            ) { Text(stringResource(R.string.login)) }
        },
        dismissButton = {
            TextButton(onClick = { backStack.pop() }) { Text(stringResource(R.string.cancel)) }
        },
    )
}
