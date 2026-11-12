package com.vayunmathur.contacts.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.NewContactRestriction
import com.vayunmathur.contacts.data.isSimAccountType
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.R as UiR

/**
 * Stateless move-to-account dialog: account picker first, then a SIM data-loss
 * warning when the destination is a SIM account that would drop fields.
 * Same-account destinations are shown disabled, as are destinations [restriction]
 * blocks (Android 16+ with a cloud default account, #916), with the reason. Dropped-field
 * labels come from [simLossLabels], resolved by the caller from strings.
 */
@Composable
fun MoveToAccountDialog(
    contactCount: Int,
    accounts: List<ContactAccount>,
    sourceAccounts: Set<ContactAccount>,
    simLabels: Map<String, String> = emptyMap(),
    selectedAccount: ContactAccount?,
    simLossLabels: List<String> = emptyList(),
    restriction: NewContactRestriction? = null,
    onSelectAccount: (ContactAccount) -> Unit,
    onClearSelection: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val destination = selectedAccount
    if (destination != null && isSimAccountType(destination.type) && simLossLabels.isNotEmpty()) {
        val simLabel = simLabels["${destination.type}|${destination.name}"]
            ?: destination.name.ifEmpty { stringResource(R.string.on_device) }
        val dropped = remember(simLossLabels) { simLossLabels.joinToString(", ") }
        // Plain AlertDialog rather than ConfirmDialog: ConfirmDialog invokes
        // onDismiss before onConfirm, and our back action clears the selection
        // the confirm callback needs.
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.move_to_sim_warning_title)) },
            text = { Text(stringResource(R.string.move_to_sim_warning_body, simLabel, dropped)) },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        stringResource(R.string.move_anyway),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                // Back returns to the picker; tapping outside dismisses outright.
                TextButton(onClick = onClearSelection) {
                    Text(stringResource(UiR.string.back))
                }
            },
        )
        return
    }
    val blockedReason = restriction?.let {
        stringResource(R.string.account_unavailable_cloud_default, it.cloudAccount.name)
    }
    fun isBlocked(account: ContactAccount) = restriction?.blocks(account.name, account.type) == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_account)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                Text(
                    pluralStringResource(R.plurals.move_to_account_subtitle, contactCount, contactCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(accounts, key = { "${it.type}|${it.name}" }) { account ->
                        val blocked = isBlocked(account)
                        DestinationRow(
                            account = account,
                            simLabels = simLabels,
                            selected = destination == account,
                            enabled = account !in sourceAccounts && !blocked,
                            disabledReason = blockedReason.takeIf { blocked },
                            onClick = { onSelectAccount(account) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = destination != null && destination !in sourceAccounts && !isBlocked(destination),
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.move))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.cancel))
            }
        },
    )
}

@Composable
private fun DestinationRow(
    account: ContactAccount,
    simLabels: Map<String, String>,
    selected: Boolean,
    enabled: Boolean,
    disabledReason: String?,
    onClick: () -> Unit,
) {
    val key = "${account.type}|${account.name}"
    val onDevice = stringResource(R.string.on_device)
    val label = simLabels[key] ?: account.name.ifEmpty { onDevice }
    val supporting = when {
        disabledReason != null -> disabledReason
        simLabels.containsKey(key) -> null
        account.type.isNotBlank() -> account.type
        else -> null
    }
    ListItem(
        content = {
            Text(
                label,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = if (selected) {
            { IconCheck() }
        } else null,
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
