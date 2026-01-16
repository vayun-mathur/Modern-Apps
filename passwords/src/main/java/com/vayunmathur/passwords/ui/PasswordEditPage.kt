package com.vayunmathur.passwords.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.passwords.Password
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.R


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordEditPage(backStack: NavBackStack<Route>, pass: Password, viewModel: DatabaseViewModel) {
    var name by remember { mutableStateOf(pass.name) }
    var userId by remember { mutableStateOf(pass.userId) }
    var password by remember { mutableStateOf(pass.password) }
    var totp by remember { mutableStateOf(pass.totpSecret ?: "") }
    var websitesText by remember { mutableStateOf(pass.websites.joinToString(",")) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (pass.id == null) "Add Password" else "Edit Password") })
    }, floatingActionButton = {
        FloatingActionButton(onClick = {
            // validation
            if (name.isBlank() || userId.isBlank()) {
                return@FloatingActionButton
            }
            val websites = websitesText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val newPass = pass.copy(
                name = name,
                userId = userId,
                password = password,
                totpSecret = totp.ifBlank { null },
                websites = websites
            )
            if (pass.id == 0L) {
                viewModel.upsert(newPass) { id ->
                    // navigate to detail
                    backStack.add(Route.PasswordPage(newPass.copy(id = id)))
                }
            } else {
                viewModel.upsert(newPass) {
                    backStack.removeLastOrNull()
                    backStack.add(Route.PasswordPage(newPass))
                }
            }
        }) {
            IconSave()
        }
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues).padding(16.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = userId, onValueChange = { userId = it }, label = { Text("User ID / Email") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = totp, onValueChange = { totp = it }, label = { Text("TOTP Secret") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = websitesText, onValueChange = { websitesText = it }, label = { Text("Websites (comma-separated)") }, modifier = Modifier.fillMaxWidth())
        }
    }
}