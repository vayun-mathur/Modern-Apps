package com.vayunmathur.passwords.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.setLast
import com.vayunmathur.passwords.Password
import com.vayunmathur.passwords.Route
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordEditPage(backStack: NavBackStack<Route>, pass: Password, viewModel: DatabaseViewModel) {
    var name by remember { mutableStateOf(pass.name) }
    var userId by remember { mutableStateOf(pass.userId) }
    var password by remember { mutableStateOf(pass.password) }
    var totp by remember { mutableStateOf(pass.totpSecret ?: "") }
    var websitesText by remember { mutableStateOf(pass.websites.joinToString(",")) }
    var showPassword by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pass.isNew()) "Add Password" else "Edit Password") },
                navigationIcon = {
                    IconNavigation {
                        backStack.removeLastOrNull()
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                // basic validation
                if (name.isBlank() || userId.isBlank()) {
                    scope.launch { snackbarHostState.showSnackbar("Name and User ID cannot be empty") }
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

                if (pass.isNew()) {
                    viewModel.upsert(newPass) { id ->
                        backStack.setLast(Route.PasswordPage(newPass.copy(id = id)))
                        scope.launch { snackbarHostState.showSnackbar("Saved") }
                    }
                } else {
                    viewModel.upsert(newPass) {
                        backStack.removeLastOrNull()
                        scope.launch { snackbarHostState.showSnackbar("Saved") }
                    }
                }
            }) {
                IconSave()
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(Modifier.padding(paddingValues).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = userId, onValueChange = { userId = it }, label = { Text("User ID / Email") }, modifier = Modifier.fillMaxWidth())
                }
            }

            Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                        }
                    )

                    OutlinedTextField(value = totp, onValueChange = { totp = it }, label = { Text("TOTP Secret") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions.Default)
                }
            }

            Card(shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = websitesText, onValueChange = { websitesText = it }, label = { Text("Websites (comma-separated)") }, modifier = Modifier.fillMaxWidth())

                    // websites preview as simple chips (buttons)
                    val websites = websitesText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (websites.isNotEmpty()) {
                        Row(Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (w in websites) {
                                AssistChip(onClick = { /* maybe open or edit site */ }, label = { Text(w) })
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(56.dp))
        }
    }
}