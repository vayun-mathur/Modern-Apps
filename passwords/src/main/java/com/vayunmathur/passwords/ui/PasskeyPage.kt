package com.vayunmathur.passwords.ui

import android.text.format.DateFormat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberMessenger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconLink
import com.vayunmathur.library.ui.IconLinkOff
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.domain.PasskeyLink
import com.vayunmathur.passwords.domain.link
import com.vayunmathur.passwords.domain.mergeCredentials
import com.vayunmathur.passwords.platform.PasswordsViewModel
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasskeyPage(
    backStack: NavBackStack<Route>,
    id: Long,
    viewModel: PasswordsViewModel,
) {
    val passkeys by viewModel.passkeys.collectAsState()
    val passkey = passkeys.firstOrNull { it.id == id }

    if (passkey == null) {
        return
    }

    val passwords by viewModel.passwords.collectAsState()
    // Resolved through the same merge the list uses, so the two never disagree.
    val linkedSyncId = remember(passwords, passkeys) {
        mergeCredentials(passwords, passkeys).passkeysByPasswordSyncId
            .entries.firstOrNull { (_, list) -> list.any { it.id == id } }?.key
    }
    val linkedTo = passwords.firstOrNull { it.syncId == linkedSyncId }
    val link = passkey.link()
    val messenger = rememberMessenger()
    var picking by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val mediumDate = DateFormat.getMediumDateFormat(context)
    val shortTime = DateFormat.getTimeFormat(context)
    val dateFormat = { ms: Long -> mediumDate.format(Date(ms)) + " " + shortTime.format(Date(ms)) }

    if (picking) {
        PasskeyLinkDialog(
            passwords = passwords,
            onDismiss = { picking = false },
            onSelect = { password ->
                picking = false
                viewModel.setPasskeyLink(passkey, PasskeyLink.To(password.syncId))
                messenger.show(
                    context.getString(
                        R.string.passkey_linked_message,
                        password.name.ifBlank { context.getString(R.string.no_name) },
                    )
                )
            },
        )
    }

    DetailScaffold(
        title = passkey.rpName.ifBlank { stringResource(R.string.passkey_detail_title) },
        backStack = backStack,
        actions = {
            IconButton(onClick = { viewModel.deletePasskey(passkey); backStack.pop() }) {
                IconDelete()
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.passkey_link_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        linkedTo != null ->
                            linkedTo.name.ifBlank { stringResource(R.string.no_name) }
                        link is PasskeyLink.Detached -> stringResource(R.string.passkey_link_detached)
                        else -> stringResource(R.string.passkey_link_none)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (linkedTo != null) {
                    Text(
                        stringResource(
                            if (link is PasskeyLink.To) R.string.passkey_link_manual
                            else R.string.passkey_link_auto
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (linkedTo != null) {
                        TextButton(onClick = {
                            viewModel.setPasskeyLink(passkey, PasskeyLink.Detached)
                            messenger.show(context.getString(R.string.passkey_unlinked_message))
                        }) {
                            IconLinkOff()
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.passkey_disconnect))
                        }
                    } else {
                        TextButton(onClick = { picking = true }) {
                            IconLink()
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.passkey_link_dialog_title))
                        }
                    }
                    if (link is PasskeyLink.Detached) {
                        TextButton(onClick = { viewModel.setPasskeyLink(passkey, PasskeyLink.Auto) }) {
                            Text(stringResource(R.string.passkey_link_use_auto))
                        }
                    }
                }
            }
        }
        DetailCard(
            stringResource(R.string.passkey_rp_name),
            passkey.rpName,
            sharedKey = "passkey-name-${passkey.id}",
        )
        DetailCard(stringResource(R.string.passkey_rp_id), passkey.rpId)
        DetailCard(
            stringResource(R.string.passkey_user_name),
            passkey.userName,
            sharedKey = "passkey-user-${passkey.id}",
        )
        DetailCard(stringResource(R.string.passkey_user_display_name), passkey.userDisplayName)
        DetailCard(
            stringResource(R.string.passkey_credential_id),
            passkey.credentialId.let { if (it.length > 20) it.take(20) + "…" else it }
        )
        DetailCard(stringResource(R.string.passkey_created), dateFormat(passkey.creationTime))
        DetailCard(stringResource(R.string.passkey_last_used), dateFormat(passkey.lastUsedTime))
    }
}

@Composable
private fun DetailCard(label: String, value: String, sharedKey: Any? = null) {
    Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                value.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = if (sharedKey == null) Modifier else Modifier.sharedText(sharedKey),
            )
        }
    }
}
