package com.vayunmathur.passwords.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconKey
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.util.tryOrDefault
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.domain.TOTP
import com.vayunmathur.passwords.domain.mergeCredentials
import com.vayunmathur.passwords.platform.MenuUiState
import com.vayunmathur.passwords.platform.PasswordsActions

@Composable
fun MenuScreen(
    backStack: NavBackStack<Route>,
    state: MenuUiState,
    actions: PasswordsActions,
) {
    val items: List<CredentialItem> = remember(state.passwords, state.passkeys) {
        val merged = mergeCredentials(state.passwords, state.passkeys)
        state.passwords.map {
            CredentialItem.PasswordItem(it, merged.passkeysByPasswordSyncId[it.syncId].orEmpty())
        } + merged.standalone.map { CredentialItem.PasskeyItem(it) }
    }

    ListPage<CredentialItem, Route, Route.PasswordEditPage>(backStack, items, "Passwords", {
        when (it) {
            // Same keys as the detail page, so the row's name and user travel there rather than
            // crossfading.
            is CredentialItem.PasswordItem -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    it.password.name.ifBlank { stringResource(R.string.no_name) },
                    modifier = Modifier.sharedText("password-name-${it.password.id}"),
                )
                if (it.passkeys.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    IconKey(Modifier.size(14.dp))
                    if (it.passkeys.size > 1) {
                        Text(
                            stringResource(R.string.passkey_count, it.passkeys.size),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            is CredentialItem.PasskeyItem -> Row(verticalAlignment = Alignment.CenterVertically) {
                IconKey(Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    it.passkey.rpName.ifBlank { it.passkey.rpId },
                    modifier = Modifier.sharedText("passkey-name-${it.passkey.id}"),
                )
            }
        }
    }, {
        when (it) {
            is CredentialItem.PasswordItem -> Text(
                it.password.username.ifBlank { it.password.email },
                modifier = Modifier.sharedText("password-user-${it.password.id}"),
            )
            is CredentialItem.PasskeyItem -> Text(
                it.passkey.userName,
                modifier = Modifier.sharedText("passkey-user-${it.passkey.id}"),
            )
        }
    }, {
        when (val item = items.firstOrNull { i -> i.id == it }) {
            is CredentialItem.PasswordItem -> Route.PasswordPage(item.password.id)
            is CredentialItem.PasskeyItem -> Route.PasskeyPage(item.passkey.id)
            null -> Route.Menu
        }
    }, editPage = { Route.PasswordEditPage(0) }, settingsPage = Route.Settings, trailingContent = {
        if (it is CredentialItem.PasswordItem) {
            val password = it.password
            if (password.totpSecret.isNullOrBlank()) return@ListPage
            val secret = password.totpSecret
            // Both reads of the ticker happen here, in the trailing slot's own restart scope, so
            // the row's name, subtitle and ListItem layout are untouched by the tick.
            val timeBucket = state.now() / 1000 / 30
            val currentCode = remember(secret, timeBucket) {
                tryOrDefault("----") { TOTP.generate(secret, timeBucket * 30) }
            }
            Row(Modifier.clickable {
                actions.copyToClipboard("totp", currentCode)
            }.wrapContentHeight(), verticalAlignment = Alignment.CenterVertically) {
                Text(currentCode, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        { (30000L - state.now() % 30000L) / 30000f },
                        Modifier.size(40.dp),
                    )
                    IconCopy(Modifier.size(16.dp))
                }
            }
        }
    }, searchEnabled = true, searchString = {
        when (it) {
            is CredentialItem.PasswordItem ->
                "${it.password.name} ${it.password.username} ${it.password.email} " +
                    it.password.websites.joinToString(" ") + " " +
                    // A merged passkey no longer has a row of its own, so searching for the site
                    // has to reach it through the password.
                    it.passkeys.joinToString(" ") { pk -> "${pk.rpName} ${pk.rpId} ${pk.userName}" }
            is CredentialItem.PasskeyItem -> "${it.passkey.rpName} ${it.passkey.rpId} ${it.passkey.userName}"
        }
    }, loading = state.loading)
}
