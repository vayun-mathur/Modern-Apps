package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(viewModel: ContactViewModel, backStack: NavBackStack<Route>) {
    val accounts by viewModel.accounts.collectAsState()
    val hiddenAccounts by viewModel.hiddenAccounts.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconNavigation(backStack) }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { backStack.add(Route.AddAccountDialog) }) {
                IconAdd()
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.visible_accounts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(accounts) { account ->
                val isVisible = account.name !in hiddenAccounts
                val onDevice = stringResource(R.string.on_device)
                ListItem(
                    headlineContent = { Text(account.name.ifEmpty { onDevice }) },
                    supportingContent = { Text(account.type) },
                    trailingContent = {
                        Checkbox(
                            checked = isVisible,
                            onCheckedChange = { viewModel.setAccountVisibility(account.name, it) }
                        )
                    }
                )
                HorizontalDivider()
            }
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}
