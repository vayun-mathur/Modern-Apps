package com.vayunmathur.everysync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.provider.AuthType
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_account_title)) },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        IconBack()
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(ProviderRegistry.all, key = { it.id }) { provider ->
                ListItem(
                    modifier = Modifier.clickable {
                        when (provider.authType) {
                            AuthType.OAUTH -> {
                                viewModel.startOAuth(provider.id)
                                // OAuth continues in a Custom Tab and returns via
                                // OAuthCallbackActivity → MainActivity, which retains
                                // this back stack. Reset to the accounts list now so
                                // the user lands on home (not here) when they return.
                                backStack.reset(Route.Accounts)
                            }
                            AuthType.DAV -> backStack.add(Route.DavLogin(provider.id))
                            AuthType.HEALTH_CONNECT -> viewModel.addHealthConnectAccount(provider.id) { backStack.pop() }
                        }
                    },
                    leadingContent = { provider.icon() },
                    content = { Text(provider.displayName) },
                    supportingContent = if (provider.viaHealthConnect) {
                        { Text(stringResource(R.string.provider_via_health_connect)) }
                    } else null,
                )
            }
        }
    }
}
