package com.vayunmathur.everysync.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.everysync.platform.AccountDetailActions
import com.vayunmathur.everysync.platform.AccountDetailUiState
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.everysync.sync.SnapshotStore.SnapshotMeta
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText

/** Binds [EverySyncViewModel] and the back stack to the stateless [AccountDetailScreen]. */
@Composable
fun AccountDetailScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel, accountName: String) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val config = accounts.firstOrNull { it.accountName == accountName }
    val vaults by viewModel.vaults.vaults.collectAsStateWithLifecycle()
    val deviceId by viewModel.vaults.deviceId.collectAsStateWithLifecycle()
    val vaultUi by viewModel.vaults.vaultUi.collectAsStateWithLifecycle()
    val safetyCode by viewModel.safetyCode.collectAsStateWithLifecycle()
    val computingCode by viewModel.computingCode.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val restoring by viewModel.restoring.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.vaults.load()
        viewModel.loadSnapshots()
    }
    val actions = remember(viewModel, backStack) {
        object : AccountDetailActions {
            override fun toggleType(accountName: String, type: DataType, enabled: Boolean) =
                viewModel.toggleType(accountName, type, enabled)

            override fun syncNow(accountName: String) = viewModel.syncNow(accountName)

            override fun restoreSnapshot(snapshot: SnapshotMeta) = viewModel.restoreSnapshot(snapshot)

            override fun snapshotNow() = viewModel.snapshotNow()

            override fun removeAccount(accountName: String) {
                viewModel.removeAccount(accountName)
                backStack.pop()
            }

            override fun back() = backStack.pop()

            override fun createVault(dataType: String, title: String) {
                viewModel.vaults.createVault(dataType, title) { ok ->
                    viewModel.announce(if (ok) R.string.vault_created else R.string.vault_create_failed)
                }
            }

            override fun refreshVaults() {
                viewModel.vaults.refreshInbox()
            }

            override fun sendInvite(vaultId: String, recipientId: String, role: String, onResult: (Boolean) -> Unit) =
                viewModel.vaults.sendInvite(vaultId, recipientId, role, onResult)

            override fun approveRequest(vaultId: String, requesterId: String) =
                viewModel.vaults.approveRequest(vaultId, requesterId)

            override fun denyRequest(vaultId: String, requesterId: String) =
                viewModel.vaults.denyRequest(vaultId, requesterId)

            override fun computeSafetyCode(recipientId: String) = viewModel.computeSafetyCode(recipientId)

            override fun offerableRoles(dataType: String) = viewModel.offerableRoles(dataType)
        }
    }

    AccountDetailScreen(
        state = AccountDetailUiState(
            accountName = accountName,
            providerId = config?.providerId,
            enabledTypes = config?.enabledTypes.orEmpty(),
            vaults = vaults.mapNotNull { vaultUi[it.vaultId] },
            deviceId = deviceId,
            safetyCode = safetyCode,
            computingCode = computingCode,
            syncErrorText = config?.let { viewModel.syncErrorText(accountName) },
            snapshots = snapshots.filter { snap ->
                vaults.any { it.vaultId == snap.vaultId }
            },
            restoring = restoring,
        ),
        actions = actions,
    )
}

/**
 * The per-account screen, with no dependency on the ViewModel or the back stack so it can
 * be rendered from a `@Preview` — see `src/screenshotTest`, which is where the store
 * listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(state: AccountDetailUiState, actions: AccountDetailActions) {
    val provider = state.providerId?.let { ProviderRegistry.get(it) }

    DetailScaffold(
        title = {
            Text(
                state.accountName,
                modifier = Modifier.sharedText("everysync-title-${state.accountName}"),
            )
        },
        onNavigateBack = { actions.back() },
    ) {
        if (provider == null) {
            Text(stringResource(R.string.no_accounts))
            return@DetailScaffold
        }
        state.syncErrorText?.let { errorText ->
            SyncErrorBanner(errorText = errorText)
            Spacer(Modifier.height(16.dp))
        }
        if (DataType.CONTACTS in provider.capabilities) {
            TypeToggle(R.string.sync_contacts, DataType.CONTACTS in state.enabledTypes) {
                actions.toggleType(state.accountName, DataType.CONTACTS, it)
            }
        }
        if (DataType.CALENDAR in provider.capabilities) {
            TypeToggle(R.string.sync_calendar, DataType.CALENDAR in state.enabledTypes) {
                actions.toggleType(state.accountName, DataType.CALENDAR, it)
            }
        }
        if (DataType.PASSWORDS in provider.capabilities) {
            TypeToggle(R.string.sync_passwords, DataType.PASSWORDS in state.enabledTypes) {
                actions.toggleType(state.accountName, DataType.PASSWORDS, it)
            }
        }
        if (DataType.NOTES in provider.capabilities) {
            TypeToggle(R.string.sync_notes, DataType.NOTES in state.enabledTypes) {
                actions.toggleType(state.accountName, DataType.NOTES, it)
            }
        }
        if (DataType.TODOS in provider.capabilities) {
            TypeToggle(R.string.sync_todos, DataType.TODOS in state.enabledTypes) {
                actions.toggleType(state.accountName, DataType.TODOS, it)
            }
        }

        Button(
            onClick = { actions.syncNow(state.accountName) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.sync_now)) }

        Spacer(Modifier.height(16.dp))
        VaultSharingSection(
            vaults = state.vaults,
            deviceId = state.deviceId,
            safetyCode = state.safetyCode,
            computingCode = state.computingCode,
            actions = actions,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { actions.removeAccount(state.accountName) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.remove_account)) }

        if (state.snapshots.isNotEmpty() || state.vaults.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SnapshotSection(
                snapshots = state.snapshots,
                restoring = state.restoring,
                onRestore = { actions.restoreSnapshot(it) },
                onSnapshotNow = { actions.snapshotNow() },
            )
        }
    }
}

@Composable
private fun TypeToggle(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsSwitchRow(
        title = stringResource(labelRes),
        checked = checked,
        onCheckedChange = onChange,
    )
}
