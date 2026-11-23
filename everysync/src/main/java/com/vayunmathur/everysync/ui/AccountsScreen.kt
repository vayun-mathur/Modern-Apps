package com.vayunmathur.everysync.ui

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconProvider
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.everysync.platform.AccountRow
import com.vayunmathur.everysync.platform.AccountsActions
import com.vayunmathur.everysync.platform.AccountsUiState
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.is24Hour
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import kotlin.time.Instant

/** Binds [EverySyncViewModel] and the back stack to the stateless [AccountsScreen]. */
@Composable
fun AccountsScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel) {
    val permissions = arrayOf(
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.WRITE_CONTACTS,
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
    )
    PermissionsChecker(permissions, stringResource(R.string.need_permissions)) {
        val accounts by viewModel.accounts.collectAsStateWithLifecycle()
        val hasLoaded by viewModel.hasLoadedAccounts.collectAsStateWithLifecycle()
        val syncing by viewModel.syncing.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val actions = remember(viewModel, backStack) {
            object : AccountsActions {
                override fun syncNow(accountName: String) = viewModel.syncNow(accountName)
                override fun openAccount(accountName: String) {
                    if (backStack.last() is Route.AccountDetail) backStack.setLast(Route.AccountDetail(accountName))
                    else backStack.add(Route.AccountDetail(accountName))
                }
                override fun openAddAccount() = backStack.add(Route.AddAccount)
                override fun openSettings() = backStack.add(Route.Settings)
            }
        }
        AccountsScreen(
            state = AccountsUiState(
                hasLoaded = hasLoaded,
                accounts = accounts.map { account ->
                    AccountRow(
                        accountName = account.accountName,
                        providerId = account.providerId,
                        syncing = account.accountName in syncing,
                        lastSyncError = account.lastSyncError,
                        lastSyncedAt = account.lastSyncEpochMs
                            .takeIf { it > 0 }
                            ?.let { formatTime(context, it) },
                    )
                },
            ),
            actions = actions,
        )
    }
}

/**
 * The accounts list, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(state: AccountsUiState, actions: AccountsActions) {
    AppScaffold(
        isRefreshing = !state.hasLoaded,
        title = stringResource(R.string.accounts_title),
        actions = {
            IconButton(onClick = { actions.openSettings() }) {
                IconSettings()
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { actions.openAddAccount() }) {
                IconAdd()
            }
        },
    ) { padding ->
        if (!state.hasLoaded && state.accounts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding))
        } else if (state.accounts.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.no_accounts),
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(state.accounts, key = { it.accountName }) { account ->
                    val provider = ProviderRegistry.get(account.providerId)
                    ListItem(
                        modifier = Modifier.clickable { actions.openAccount(account.accountName) },
                        content = {
                            Text(
                                account.accountName,
                                modifier = Modifier.sharedText(
                                    "everysync-title-${account.accountName}"
                                ),
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(provider?.displayName ?: account.providerId)
                                Text(
                                    when {
                                        account.syncing -> stringResource(R.string.syncing)
                                        account.lastSyncError != null -> account.lastSyncError
                                        account.lastSyncedAt != null ->
                                            stringResource(R.string.last_synced, account.lastSyncedAt)
                                        else -> stringResource(R.string.never_synced)
                                    },
                                )
                            }
                        },
                        leadingContent = {
                            (provider?.icon ?: { IconProvider() })()
                        },
                        trailingContent = {
                            if (account.syncing) {
                                Text(stringResource(R.string.syncing))
                            } else {
                                IconButton(onClick = { actions.syncNow(account.accountName) }) {
                                    IconRefresh()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun formatTime(context: Context, millis: Long): String =
    DateString.dateTime(Instant.fromEpochMilliseconds(millis), is24Hour(context))
