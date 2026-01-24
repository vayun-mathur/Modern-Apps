package com.vayunmathur.passwords.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.setLast
import com.vayunmathur.passwords.Password
import com.vayunmathur.passwords.Route
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordEditPage(backStack: NavBackStack<Route>, id: Long, viewModel: DatabaseViewModel) {
    val pass by viewModel.get<Password>(id) { Password() }
    var name by remember { mutableStateOf(pass.name) }
    var userId by remember { mutableStateOf(pass.userId) }
    var password by remember { mutableStateOf(pass.password) }
    var totp by remember { mutableStateOf(pass.totpSecret ?: "") }
    // websites: maintain list and an input box for new site
    val websitesList = remember { mutableStateListOf<String>().apply { addAll(pass.websites) } }
    var websiteInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    fun addWebsiteFromInput() {
        val candidate = websiteInput.trim()
        if (candidate.isNotEmpty() && !websitesList.contains(candidate)) {
            websitesList.add(candidate)
        }
        websiteInput = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pass.isNew()) "Add Password" else "Edit Password") },
                navigationIcon = {
                    IconNavigation(backStack)
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

                val newPass = pass.copy(
                    name = name,
                    userId = userId,
                    password = password,
                    totpSecret = totp.ifBlank { null },
                    websites = websitesList.toList()
                )

                if (pass.isNew()) {
                    viewModel.upsert(newPass) { id ->
                        backStack.setLast(Route.PasswordPage(id))
                    }
                } else {
                    viewModel.upsert(newPass) {
                        backStack.removeLastOrNull()
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
                    // Input for websites: pressing IME Done (Enter) adds to list
                    OutlinedTextField(
                        value = websiteInput,
                        onValueChange = { websiteInput = it },
                        label = { Text("Add website") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            addWebsiteFromInput()
                            focusManager.clearFocus()
                        })
                    )

                    // websites preview as chips with remove X
                    if (websitesList.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for ((index, w) in websitesList.withIndex()) {
                                InputChip(true, {}, label = { Text(w)}, modifier = Modifier.padding(vertical = 4.dp),
                                    trailingIcon = {
                                        Box(Modifier.clickable { websitesList.removeAt(index) }) {
                                            IconClose()
                                        }
                                    })
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(56.dp))
        }
    }
}