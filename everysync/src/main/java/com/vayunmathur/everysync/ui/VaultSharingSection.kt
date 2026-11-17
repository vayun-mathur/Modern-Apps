package com.vayunmathur.everysync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.platform.AccountDetailActions
import com.vayunmathur.everysync.platform.VaultUiState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.ShareVaultDialog
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.VaultJoinRequest
import com.vayunmathur.library.ui.VaultMember
import com.vayunmathur.library.ui.rememberMessenger

/**
 * The personal-vaults block on the account detail screen: create-vault plus
 * one row per vault with share (dialog), members and pending requests.
 *
 * Vault creation and sharing live ONLY in EverySync — contacts, calendar,
 * passwords and notes apps get no share buttons or dialogs of their own.
 */
@Composable
fun VaultSharingSection(
    vaults: List<VaultUiState>,
    deviceId: String,
    safetyCode: String?,
    computingCode: Boolean,
    actions: AccountDetailActions,
    modifier: Modifier = Modifier,
) {
    var newTitle by remember { mutableStateOf("") }
    var sharing by remember { mutableStateOf<String?>(null) }

    Column(modifier) {
        Text(
            stringResource(R.string.vaults_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = newTitle,
            onValueChange = { newTitle = it },
            label = { Text(stringResource(R.string.vault_new_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    actions.createVault("contacts", newTitle.trim())
                    newTitle = ""
                },
                enabled = newTitle.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.vault_create_contacts)) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    actions.createVault("calendar", newTitle.trim())
                    newTitle = ""
                },
                enabled = newTitle.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.vault_create_calendar)) }
        }
        vaults.forEach { vault ->
            Spacer(Modifier.height(8.dp))
            VaultRow(
                vault = vault,
                onShare = { sharing = vault.meta.vaultId },
                onRefresh = { actions.refreshVaults() },
            )
        }
        if (vaults.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.vaults_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val sharingVault = sharing?.let { id -> vaults.firstOrNull { it.meta.vaultId == id } }
    if (sharingVault != null) {
        VaultShareDialog(
            vault = sharingVault,
            deviceId = deviceId,
            safetyCode = safetyCode,
            computingCode = computingCode,
            actions = actions,
            onDismiss = { sharing = null },
        )
    }
}

/** Binds one [VaultUiState] to the shared [ShareVaultDialog]. */
@Composable
private fun VaultShareDialog(
    vault: VaultUiState,
    deviceId: String,
    safetyCode: String?,
    computingCode: Boolean,
    actions: AccountDetailActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val shareLinkChooser = stringResource(R.string.share_link_chooser)
    val inviteSent = stringResource(R.string.invite_sent)
    val inviteFailed = stringResource(R.string.invite_failed)
    ShareVaultDialog(
        vaultTitle = vault.meta.title,
        shareLink = vault.shareLink,
        deviceId = deviceId,
        roles = actions.offerableRoles(vault.meta.dataType),
        members = vault.members.map { VaultMember(it.id, it.name, it.role) },
        pendingRequests = vault.requests.map { VaultJoinRequest(it.requesterId, it.name) },
        isOwner = vault.meta.isOwner,
        safetyCode = safetyCode,
        computingCode = computingCode,
        onShareLink = {
            ExternalIntents.shareText(context, vault.shareLink, shareLinkChooser)
        },
        onSendInvite = { recipientId, role ->
            actions.sendInvite(vault.meta.vaultId, recipientId, role) { ok ->
                messenger.show(if (ok) inviteSent else inviteFailed)
            }
        },
        onApproveRequest = { requesterId -> actions.approveRequest(vault.meta.vaultId, requesterId) },
        onDenyRequest = { requesterId -> actions.denyRequest(vault.meta.vaultId, requesterId) },
        onComputeSafetyCode = { recipientId -> actions.computeSafetyCode(recipientId) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun VaultRow(vault: VaultUiState, onShare: () -> Unit, onRefresh: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        ListItem(
            content = {
                Column {
                    Text(vault.meta.title)
                    Text(
                        vault.meta.dataType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefresh) { IconRefresh() }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = onShare) {
                        IconShare()
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.vault_share))
                    }
                }
            },
        )
    }
}
