package com.vayunmathur.email.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.R
import com.vayunmathur.email.data.OutlookOAuth
import com.vayunmathur.email.data.PROVIDER_CUSTOM
import com.vayunmathur.email.data.PROVIDER_OUTLOOK
import com.vayunmathur.email.data.PROVIDER_PRESETS
import com.vayunmathur.email.data.ProviderPreset
import com.vayunmathur.email.platform.ServerConfig
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch

/**
 * Add-account form for one provider (`Route.AddAccountForm(providerId)`):
 * Outlook OAuth for Outlook, app password for the rest. Pushed by the
 * provider picker, so Back is a plain stack pop to the picker. An unknown
 * provider id (a stale restored destination) pops straight back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountFormScreen(
    providerId: String,
    onBack: (() -> Unit)?,
    onAccountAdded: () -> Unit,
) {
    val selectedProvider = PROVIDER_PRESETS.firstOrNull { it.id == providerId }
    if (selectedProvider == null) {
        LaunchedEffect(providerId) { onBack?.invoke() }
        return
    }
    AppScaffold(
        title = selectedProvider.displayName,
        navigationIcon = {
            if (onBack != null) { IconNavigation(onBack) }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (selectedProvider.id == PROVIDER_OUTLOOK) {
                OutlookOAuthForm(
                    preset = selectedProvider,
                    onAccountAdded = onAccountAdded,
                )
            } else {
                PasswordForm(
                    preset = selectedProvider,
                    onAccountAdded = onAccountAdded,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlookOAuthForm(
    preset: ProviderPreset,
    onAccountAdded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountAddedMsg = stringResource(R.string.account_added)
    var emailHint by rememberSaveable { mutableStateOf("") }
    var useAppPassword by rememberSaveable { mutableStateOf(false) }
    var appPassword by rememberSaveable { mutableStateOf("") }
    var appPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.sign_in_with, preset.displayName), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                preset.instructions.forEach { line -> Text("• $line", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(4.dp))
                Text("Client: ${com.vayunmathur.email.BuildConfig.OUTLOOK_OAUTH_CLIENT_ID.take(12)}… Redirect: com.vayunmathur.email://oauth", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        OutlinedTextField(
            value = emailHint,
            onValueChange = { emailHint = it.trim() },
            label = { Text(stringResource(R.string.email_address_optional)) },
            placeholder = { Text("Optional — pre-fills Microsoft login") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { OutlookOAuth.start(context, emailHint) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sign_in_with, preset.displayName))
        }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { useAppPassword = !useAppPassword }) {
            Checkbox(checked = useAppPassword, onCheckedChange = { useAppPassword = it })
            Text(stringResource(R.string.use_app_password_instead), style = MaterialTheme.typography.bodyMedium)
        }

        if (useAppPassword) {
            OutlinedTextField(
                value = appPassword,
                onValueChange = { appPassword = it },
                label = { Text(stringResource(R.string.app_password)) },
                singleLine = true,
                visualTransformation = if (appPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { appPasswordVisible = !appPasswordVisible }) {
                        Text(if (appPasswordVisible) stringResource(R.string.hide) else stringResource(R.string.show))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                enabled = !working && emailHint.isNotBlank() && appPassword.isNotBlank(),
                onClick = {
                    error = null; working = true
                    scope.launch {
                        val result = testAndPersistAccount(
                            context = context,
                            providerId = preset.id,
                            email = emailHint,
                            username = "",
                            password = appPassword,
                            imap = preset.imap ?: ServerConfig("outlook.office365.com", 993, true),
                            smtp = preset.smtp ?: ServerConfig("smtp-mail.outlook.com", 587, false),
                        )
                        working = false
                        if (result == null) {
                            AppMessages.show(accountAddedMsg); onAccountAdded()
                        } else error = result
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.testing_connection))
                } else Text(stringResource(R.string.test_connection_and_save))
            }
        }

        preset.appPasswordHelpUrl?.let { url ->
            TextButton(onClick = { openUrl(context, url) }) { Text(stringResource(R.string.open_app_password_help, preset.displayName)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordForm(
    preset: ProviderPreset,
    onAccountAdded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountAddedMsg = stringResource(R.string.account_added)

    var email by rememberSaveable { mutableStateOf("") }
    var useDifferentUsername by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    var imapHost by rememberSaveable { mutableStateOf(preset.imap?.host ?: "") }
    var imapPort by rememberSaveable { mutableStateOf((preset.imap?.port ?: 993).toString()) }
    var imapUseSsl by rememberSaveable { mutableStateOf(preset.imap?.useSsl ?: true) }
    var smtpHost by rememberSaveable { mutableStateOf(preset.smtp?.host ?: "") }
    var smtpPort by rememberSaveable { mutableStateOf((preset.smtp?.port ?: 465).toString()) }
    var smtpUseSsl by rememberSaveable { mutableStateOf(preset.smtp?.useSsl ?: true) }

    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (preset.instructions.isNotEmpty()) { InstructionsCard(preset = preset) }

        OutlinedTextField(value = email, onValueChange = { email = it.trim() }, label = { Text(stringResource(R.string.email_address)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        if (preset.id == PROVIDER_CUSTOM) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { useDifferentUsername = !useDifferentUsername }) {
                Checkbox(checked = useDifferentUsername, onCheckedChange = { useDifferentUsername = it })
                Text(stringResource(R.string.username_is_not_my_email), style = MaterialTheme.typography.bodyMedium)
            }
            if (useDifferentUsername) {
                OutlinedTextField(value = username, onValueChange = { username = it.trim() }, label = { Text(stringResource(R.string.username)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
            }
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (preset.id == PROVIDER_CUSTOM) stringResource(R.string.password) else stringResource(R.string.app_password)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) { Text(if (passwordVisible) stringResource(R.string.hide) else stringResource(R.string.show)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (preset.id == PROVIDER_CUSTOM) {
            Text(stringResource(R.string.imap_incoming), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            AddAccountSection(host = imapHost, onHostChange = { imapHost = it.trim() }, port = imapPort, onPortChange = { imapPort = it.filter(Char::isDigit) }, useSsl = imapUseSsl, onSslChange = { imapUseSsl = it })
            Text(stringResource(R.string.smtp_outgoing), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            AddAccountSection(host = smtpHost, onHostChange = { smtpHost = it.trim() }, port = smtpPort, onPortChange = { smtpPort = it.filter(Char::isDigit) }, useSsl = smtpUseSsl, onSslChange = { smtpUseSsl = it })
        }

        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            enabled = !working && email.isNotBlank() && password.isNotBlank() && (preset.id != PROVIDER_CUSTOM || (imapHost.isNotBlank() && smtpHost.isNotBlank() && imapPort.isNotBlank() && smtpPort.isNotBlank())),
            onClick = {
                error = null; working = true
                scope.launch {
                    val imap = preset.imap ?: ServerConfig(imapHost, imapPort.toIntOrNull() ?: 993, imapUseSsl)
                    val smtp = preset.smtp ?: ServerConfig(smtpHost, smtpPort.toIntOrNull() ?: 465, smtpUseSsl)
                    val result = testAndPersistAccount(context = context, providerId = preset.id, email = email, username = if (useDifferentUsername) username else "", password = password, imap = imap, smtp = smtp)
                    working = false
                    if (result == null) { AppMessages.show(accountAddedMsg); onAccountAdded() } else error = result
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.testing_connection))
            } else Text(stringResource(R.string.test_connection_and_save))
        }
    }
}
