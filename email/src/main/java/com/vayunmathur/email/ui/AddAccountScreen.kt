package com.vayunmathur.email.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.EmailAccount
import com.vayunmathur.email.EmailManager
import com.vayunmathur.email.R
import com.vayunmathur.email.ServerConfig
import com.vayunmathur.email.data.CredentialCrypto
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.ImapIdleService
import com.vayunmathur.email.data.OutlookOAuth
import com.vayunmathur.email.data.PROVIDER_CUSTOM
import com.vayunmathur.email.data.PROVIDER_GMAIL
import com.vayunmathur.email.data.PROVIDER_PRESETS
import com.vayunmathur.email.data.ProviderPreset
import com.vayunmathur.library.ui.IconNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add-account flow: pick a preset (Gmail / Outlook / Yahoo / iCloud / Fastmail
 * / Custom) → collect an app password (all providers). After a successful
 * "Test connection" the account is encrypted-and-persisted and a one-off
 * sync is kicked off.
 *
 * @param onBack pop the screen. Null when the screen is the first-run gate
 *   (the parent re-renders to the inbox automatically once an account exists).
 * @param onAccountAdded invoked after the account is saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    onBack: (() -> Unit)?,
    onAccountAdded: () -> Unit,
) {
    var selectedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedProvider = selectedProviderId?.let { id -> PROVIDER_PRESETS.firstOrNull { it.id == id } }

    // System back: if a provider is selected, first press returns to the
    // picker; only after deselecting does back propagate up to whatever
    // `onBack` does (pop nav stack, exit app, etc.).
    androidx.activity.compose.BackHandler(enabled = selectedProvider != null) {
        selectedProviderId = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedProvider == null) stringResource(R.string.add_account) else selectedProvider.displayName) },
                navigationIcon = {
                    val backTarget: (() -> Unit)? = when {
                        selectedProvider != null -> ({ selectedProviderId = null })
                        else -> onBack
                    }
                    if (backTarget != null) {
                        IconNavigation(backTarget)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (selectedProvider == null) {
                ProviderPicker(onPick = { selectedProviderId = it.id })
            } else if (selectedProvider.authType == "oauth2") {
                OAuthForm(preset = selectedProvider)
            } else {
                PasswordForm(
                    preset = selectedProvider,
                    onAccountAdded = onAccountAdded,
                )
            }
        }
    }
}

@Composable
private fun ProviderPicker(onPick: (ProviderPreset) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "Choose your email provider",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        PROVIDER_PRESETS.forEach { preset ->
            ElevatedCard(
                onClick = { onPick(preset) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(preset.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            preset.id == PROVIDER_CUSTOM -> "Enter IMAP/SMTP server details manually"
                            preset.authType == "oauth2" -> "Sign in with Microsoft"
                            else -> "App password"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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

    var email by rememberSaveable { mutableStateOf("") }
    var useDifferentUsername by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    // Custom-provider-only fields. Pre-filled with sensible IMAP/SMTP defaults
    // (993/465 SSL) that work for the majority of mail hosts; advanced users
    // can flip to STARTTLS via the radio buttons.
    var imapHost by rememberSaveable { mutableStateOf(preset.imap?.host ?: "") }
    var imapPort by rememberSaveable { mutableStateOf((preset.imap?.port ?: 993).toString()) }
    var imapUseSsl by rememberSaveable { mutableStateOf(preset.imap?.useSsl ?: true) }
    var smtpHost by rememberSaveable { mutableStateOf(preset.smtp?.host ?: "") }
    var smtpPort by rememberSaveable { mutableStateOf((preset.smtp?.port ?: 465).toString()) }
    var smtpUseSsl by rememberSaveable { mutableStateOf(preset.smtp?.useSsl ?: true) }

    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (preset.instructions.isNotEmpty()) {
            InstructionsCard(preset = preset)
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim() },
            label = { Text(stringResource(R.string.email_address)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        if (preset.id == PROVIDER_CUSTOM) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { useDifferentUsername = !useDifferentUsername }
            ) {
                Checkbox(
                    checked = useDifferentUsername,
                    onCheckedChange = { useDifferentUsername = it },
                )
                Text("Username is not my email", style = MaterialTheme.typography.bodyMedium)
            }
            if (useDifferentUsername) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (preset.id == PROVIDER_CUSTOM) "Password" else "App password") },
            singleLine = true,
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(if (passwordVisible) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (preset.id == PROVIDER_CUSTOM) {
            Text("IMAP (incoming)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ServerRow(
                host = imapHost, onHostChange = { imapHost = it.trim() },
                port = imapPort, onPortChange = { imapPort = it.filter(Char::isDigit) },
                useSsl = imapUseSsl, onSslChange = { imapUseSsl = it },
            )
            Text("SMTP (outgoing)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ServerRow(
                host = smtpHost, onHostChange = { smtpHost = it.trim() },
                port = smtpPort, onPortChange = { smtpPort = it.filter(Char::isDigit) },
                useSsl = smtpUseSsl, onSslChange = { smtpUseSsl = it },
            )
        }

        if (error != null) {
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            enabled = !working
                && email.isNotBlank() && password.isNotBlank()
                && (preset.id != PROVIDER_CUSTOM
                    || (imapHost.isNotBlank() && smtpHost.isNotBlank()
                        && imapPort.isNotBlank() && smtpPort.isNotBlank())),
            onClick = {
                error = null
                working = true
                scope.launch {
                    val imap = preset.imap
                        ?: ServerConfig(imapHost, imapPort.toIntOrNull() ?: 993, imapUseSsl)
                    val smtp = preset.smtp
                        ?: ServerConfig(smtpHost, smtpPort.toIntOrNull() ?: 465, smtpUseSsl)
                    val result = testAndPersistAccount(
                        context = context,
                        providerId = preset.id,
                        email = email,
                        username = if (useDifferentUsername) username else "",
                        password = password,
                        imap = imap,
                        smtp = smtp,
                    )
                    working = false
                    if (result == null) {
                        Toast.makeText(context, "Account added", Toast.LENGTH_SHORT).show()
                        onAccountAdded()
                    } else {
                        error = result
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.testing_connection))
            } else {
                Text(stringResource(R.string.test_connection_and_save))
            }
        }
    }
}

@Composable
private fun OAuthForm(preset: ProviderPreset) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sign in with ${preset.displayName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                preset.instructions.forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(
            onClick = { OutlookOAuth.start(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign in with Microsoft")
        }
    }
}

@Composable
private fun InstructionsCard(preset: ProviderPreset) {
    val context = LocalContext.current
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How to get your app password", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            preset.instructions.forEachIndexed { i, line ->
                Text("${i + 1}. $line", style = MaterialTheme.typography.bodySmall)
            }
            preset.appPasswordHelpUrl?.let { url ->
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { openUrl(context, url) }) {
                    Text(stringResource(R.string.open_app_password_help, preset.displayName))
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    host: String, onHostChange: (String) -> Unit,
    port: String, onPortChange: (String) -> Unit,
    useSsl: Boolean, onSslChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            label = { Text(stringResource(R.string.host_label)) },
            singleLine = true,
            modifier = Modifier.weight(2f),
        )
        OutlinedTextField(
            value = port,
            onValueChange = onPortChange,
            label = { Text(stringResource(R.string.port_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Security:", modifier = Modifier.padding(end = 8.dp))
        FilterChip(
            selected = useSsl,
            onClick = { onSslChange(true) },
            label = { Text(stringResource(R.string.ssl_tls)) },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = !useSsl,
            onClick = { onSslChange(false) },
            label = { Text(stringResource(R.string.starttls)) },
        )
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}

/**
 * Try the supplied IMAP credentials by listing folders; on success encrypt the
 * password, persist the [EmailAccount], and start syncing. Returns `null` on
 * success or a human-readable error message on failure.
 */
private suspend fun testAndPersistAccount(
    context: Context,
    providerId: String,
    email: String,
    username: String,
    password: String,
    imap: ServerConfig,
    smtp: ServerConfig,
): String? = withContext(Dispatchers.IO) {
    val loginUser = username.ifBlank { email }
    try {
        EmailManager().fetchFolders(
            server = imap,
            user = loginUser,
            auth = EmailManager.AuthType.Password(password),
        )
    } catch (e: javax.mail.AuthenticationFailedException) {
        return@withContext "Authentication failed — check your email and app password."
    } catch (e: Exception) {
        return@withContext "Couldn't reach ${imap.host}:${imap.port} — ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
    }

    val (cipher, iv) = try {
        CredentialCrypto.encrypt(password)
    } catch (e: Exception) {
        return@withContext "Couldn't store password securely: ${e.message}"
    }

    val account = EmailAccount(
        email = email,
        username = username,
        provider = providerId,
        imapHost = imap.host,
        imapPort = imap.port,
        imapUseSsl = imap.useSsl,
        smtpHost = smtp.host,
        smtpPort = smtp.port,
        smtpUseSsl = smtp.useSsl,
        authType = "password",
        passwordEncrypted = cipher,
        passwordIv = iv,
    )
    EmailDatabase.getInstance(context).emailDao().insertAccount(account)
    EmailSyncWorker.schedulePeriodicSync(context)
    EmailSyncWorker.runOneOffSync(context)
    ImapIdleService.start(context)
    null
}
