package com.vayunmathur.passwords.ui
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconKey
import com.vayunmathur.library.ui.IconLink
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.platform.PasswordUiState
import com.vayunmathur.passwords.platform.PasswordsActions

@Composable
fun PasswordScreen(
    state: PasswordUiState,
    actions: PasswordsActions,
    onBack: () -> Unit = {},
    onEdit: () -> Unit = {},
    onOpenPasskey: (Long) -> Unit = {},
    /**
     * Seed for the screen's own UI-only state. The app always takes the default; a preview
     * can set it to capture the revealed form without driving the reveal button.
     */
    initialShowPassword: Boolean = false,
) {
    val password = state.password
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(initialShowPassword) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        actions.copyEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LazyListScaffold(
        topBar = {
            TopAppBar(
                title = { Text(password.name.ifBlank { stringResource(R.string.label_password) }) },
                actions = {
                    IconButton(onClick = { actions.delete(password); onBack() }) {
                        IconDelete()
                    }
                },
                navigationIcon = {
                    IconNavigation(onBack)
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onEdit) {
                IconEdit()
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        // Header
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Avatar with initial
                    val initial = password.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initial, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            password.name.ifBlank { stringResource(R.string.no_name) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.sharedText("password-name-${password.id}"),
                        )
                        Spacer(Modifier.height(4.dp))
                        val subtitle = password.username.ifBlank { password.email }
                        Text(
                            subtitle.ifBlank { stringResource(R.string.no_user) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.sharedText("password-user-${password.id}"),
                        )
                    }
                }
            }
        }

        // Login info: username / email
        if (password.username.isNotBlank() || password.email.isNotBlank()) {
            item {
                Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        if (password.username.isNotBlank()) {
                            PasswordScreenSection(
                                label = stringResource(R.string.label_username),
                                value = password.username,
                                onCopy = { actions.copyToClipboard("username", password.username) },
                            )
                        }
                        if (password.username.isNotBlank() && password.email.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                        }
                        if (password.email.isNotBlank()) {
                            PasswordScreenSection(
                                label = stringResource(UiR.string.email),
                                value = password.email,
                                onCopy = { actions.copyToClipboard("email", password.email) },
                            )
                        }
                    }
                }
            }
        }

        // Password card
        item {
            Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.label_password), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (showPassword) password.password else "•".repeat(password.password.length),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(onClick = { showPassword = !showPassword }) {
                            if (showPassword) IconVisibilityOff()
                            else IconVisible()
                        }

                        IconButton(onClick = {
                            actions.copyToClipboard("password", password.password)
                        }) {
                            IconCopy()
                        }
                    }
                }
            }
        }

        // TOTP card: show generated code and circular timer
        item {
            PasswordTotpCard(
                password = password,
                now = state.now,
                onCopy = { code -> actions.copyToClipboard("totp", code, "TOTP copied") },
            )
        }

        // Passkeys merged into this entry
        if (state.passkeys.isNotEmpty()) {
            item {
                Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.section_passkeys), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        for (passkey in state.passkeys) {
                            PasskeyRow(passkey) { onOpenPasskey(passkey.id) }
                        }
                    }
                }
            }
        }

        // Note
        if (password.note.isNotBlank()) {
            item {
                Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(UiR.string.note), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { actions.copyToClipboard("note", password.note) }) {
                                IconCopy()
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(password.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // Websites
        item {
            Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.section_websites), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (password.websites.isEmpty()) {
                        Text(stringResource(R.string.websites_none))
                    } else {
                        for (w in password.websites) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // open link
                                    val intent = Intent(Intent.ACTION_VIEW, sanitizeUrl(w).toUri())
                                    ExternalIntents.launch(context, intent)
                                }
                                .padding(vertical = 6.dp)) {
                                Text(w, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                IconLink()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasskeyRow(passkey: Passkey, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        IconKey(Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                passkey.rpName.ifBlank { passkey.rpId },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (passkey.userName.isNotBlank()) {
                Text(
                    passkey.userName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

fun sanitizeUrl(input: String): String {
    val trimmed = input.trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}
