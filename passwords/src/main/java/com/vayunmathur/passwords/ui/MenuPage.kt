package com.vayunmathur.passwords.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.Password
import com.vayunmathur.passwords.PasswordViewModel

@Composable
fun MenuPage(backStack: NavBackStack<Route>, viewModel: PasswordViewModel) {
    val list = viewModel.passwords.collectAsState()

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = {
            // navigate to edit page for creating new password
            backStack.add(Route.PasswordEditPage(Password()))
        }) {
            Text("+")
        }
    }) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            items(list.value, key = { it.id ?: it.hashCode().toLong() }) { pass ->
                PasswordListItem(pass) {
                    backStack.add(Route.PasswordPage(pass))
                }
            }
        }
    }
}

@Composable
private fun PasswordListItem(pass: Password, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        headlineContent = { Text(pass.name.ifBlank { "(no name)" }) },
        supportingContent = { Text(pass.userId) }
    )
}
