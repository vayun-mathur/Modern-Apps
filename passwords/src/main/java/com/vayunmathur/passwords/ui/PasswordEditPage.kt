package com.vayunmathur.passwords.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
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
fun PasswordEditPage(backStack: NavBackStack<Route>, id: Long, viewModel: DatabaseViewModel) {
    val passDatabase by viewModel.getNullable<Password>(id)
    val pass = passDatabase ?: Password()
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
                        Row(Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((index, w) in websitesList.withIndex()) {
                                Surface(
                                    tonalElevation = 2.dp,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(w)
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = { websitesList.removeAt(index) }, modifier = Modifier.size(28.dp)) {
                                            Text("✕")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(56.dp))
        }
    }
}