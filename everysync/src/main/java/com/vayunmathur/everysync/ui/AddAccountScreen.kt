package com.vayunmathur.everysync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.everysync.R
import com.vayunmathur.everysync.Route
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.everysync.platform.AddAccountActions
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.library.util.NavBackStack

/** Binds [EverySyncViewModel] and the back stack to the stateless [AddAccountScreen]. */
@Composable
fun AddAccountScreen(backStack: NavBackStack<Route>, viewModel: EverySyncViewModel) {
    val actions = remember(viewModel, backStack) {
        object : AddAccountActions {
            override fun addRelayAccount(providerId: String) =
                viewModel.addRelayAccount(providerId) { backStack.pop() }

            override fun back() = backStack.pop()
        }
    }
    AddAccountScreen(actions)
}

/**
 * The provider chooser. The list is [ProviderRegistry], which is static, so this screen has
 * no state of its own and can be rendered from a `@Preview` — see `src/screenshotTest`,
 * which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(actions: AddAccountActions) {
    AppScaffold(
        title = stringResource(R.string.add_account_title),
        onNavigateBack = { actions.back() },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(ProviderRegistry.all, key = { it.id }) { provider ->
                ListItem(
                    modifier = Modifier.clickable { actions.addRelayAccount(provider.id) },
                    leadingContent = { provider.icon() },
                    content = { Text(provider.displayName) },
                )
            }
        }
    }
}
