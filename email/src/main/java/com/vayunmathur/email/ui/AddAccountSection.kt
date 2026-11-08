package com.vayunmathur.email.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.R
import com.vayunmathur.email.data.CredentialCrypto
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.data.EmailSyncWorker
import com.vayunmathur.email.data.ImapIdleService
import com.vayunmathur.email.platform.EmailManager
import com.vayunmathur.email.platform.ServerConfig
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AddAccountSection(host: String, onHostChange: (String) -> Unit, port: String, onPortChange: (String) -> Unit, useSsl: Boolean, onSslChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = host, onValueChange = onHostChange, label = { Text(stringResource(R.string.host_label)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(2f))
        OutlinedTextField(value = port, onValueChange = onPortChange, label = { Text(stringResource(R.string.port_label)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.security), modifier = Modifier.padding(end = 8.dp))
        FilterChip(selected = useSsl, onClick = { onSslChange(true) }, label = { Text(stringResource(R.string.ssl_tls)) })
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = !useSsl, onClick = { onSslChange(false) }, label = { Text(stringResource(R.string.starttls)) })
    }
}

internal suspend fun testAndPersistAccount(
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
        EmailManager().fetchFolders(server = imap, user = loginUser, auth = EmailManager.AuthType.Password(password), accountEmail = email)
    } catch (ignored: Exception) {
        val msg = ignored.message?.lowercase() ?: ""
        val isAuth = ignored is com.vayunmathur.email.network.imap.ImapAuthException || msg.contains("auth") && (msg.contains("failed") || msg.contains("invalid") || msg.contains("no") || msg.contains("login"))
        if (isAuth) return@withContext "Authentication failed — check your email and app password."
        return@withContext "Couldn't reach ${imap.host}:${imap.port} — ${ignored.javaClass.simpleName}: ${ignored.message ?: "unknown"}"
    }
    val (cipher, iv) = try { CredentialCrypto.encrypt(password) } catch (ignored: Exception) { return@withContext "Couldn't store password: ${ignored.message}" }
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
    EmailRepository.get(context).insertAccount(account)
    EmailSyncWorker.scheduleHourlyNonInboxSync(context)
    EmailSyncWorker.runOneOffSync(context)
    ImapIdleService.start(context)
    null
}
