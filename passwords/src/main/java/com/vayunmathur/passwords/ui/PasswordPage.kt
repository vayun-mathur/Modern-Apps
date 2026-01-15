package com.vayunmathur.passwords.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.passwords.Password
import com.vayunmathur.passwords.PasswordViewModel
import com.vayunmathur.passwords.Route

@Composable
fun PasswordPage(backStack: NavBackStack<Route>, pass: Password, viewModel: PasswordViewModel) {
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = {
            backStack.add(Route.PasswordEditPage(pass))
        }) {
            Text("Edit")
        }

    }) { paddingValues ->
        Column(Modifier.padding(paddingValues).padding(16.dp)) {
            Text(text = "Name", style = MaterialTheme.typography.titleMedium)
            Text(text = pass.name.ifBlank { "(no name)" })
            Spacer(Modifier.height(8.dp))

            Text(text = "User ID / Email", style = MaterialTheme.typography.titleMedium)
            Text(text = pass.userId)
            Spacer(Modifier.height(8.dp))

            Text(text = "Password", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = if (showPassword) pass.password else "••••••••",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = {
                    showPassword = !showPassword
                }) {
                    Text(if (showPassword) "Hide" else "Show")
                }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("password", pass.password))
                }) {
                    Text("Copy")
                }
            }
            Spacer(Modifier.height(8.dp))

            Text(text = "TOTP Secret", style = MaterialTheme.typography.titleMedium)
            Text(text = pass.totpSecret ?: "(none)")
            Spacer(Modifier.height(8.dp))

            Text(text = "Websites", style = MaterialTheme.typography.titleMedium)
            if (pass.websites.isEmpty()) {
                Text("(none)")
            } else {
                for (w in pass.websites) {
                    Text(text = w, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}