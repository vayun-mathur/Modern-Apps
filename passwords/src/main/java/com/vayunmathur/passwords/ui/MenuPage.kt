package com.vayunmathur.passwords.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.Password
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.TOTP
import kotlinx.coroutines.delay

@Composable
fun MenuPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val passwords = viewModel.data<Password>().collectAsState()

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = {
            backStack.add(Route.PasswordEditPage(0))
        }) {
            IconAdd()
        }
    }) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            items(passwords.value, key = { it.id }) { pass ->
                PasswordListItem(pass) {
                    backStack.add(Route.PasswordPage(pass.id))
                }
            }
        }
    }
}

@Composable
private fun PasswordListItem(pass: Password, onClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (pass.totpSecret.isNullOrBlank()) {
        // No TOTP: simple list item
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 8.dp),
            headlineContent = { Text(pass.name.ifBlank { "(no name)" }) },
            supportingContent = { Text(pass.userId) }
        )
        return
    }

    // TOTP present: use identical logic to PasswordPage for smooth timer and code generation
    var currentCode by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        while (true) {
            val nowMs = System.currentTimeMillis()
            val nowSeconds = nowMs / 1000

            // 1. Calculate the current 30s bucket (Time Step)
            val timeStep = nowSeconds / 30

            // 2. Generate code based on this bucket
            currentCode = try {
                TOTP.generate(pass.totpSecret, timeStep * 30)
            } catch (_: Exception) {
                "----"
            }

            // 3. Calculate remaining time in this specific bucket
            val millisIntoStep = nowMs % 30000
            val millisRemaining = 30000 - millisIntoStep

            progress = millisRemaining / 30000f

            // Tick frequently for a smooth progress bar
            delay(50)
        }
    }

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        headlineContent = { Text(pass.name.ifBlank { "(no name)" }) },
        supportingContent = { Text(pass.userId) },
        trailingContent = {
            Row(Modifier.clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("totp", currentCode))
            }.wrapContentHeight(), verticalAlignment = Alignment.CenterVertically) {
                Text(currentCode, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator({progress}, Modifier.size(40.dp))
                    Icon(painterResource(R.drawable.content_copy_24px), contentDescription = "Copy TOTP", Modifier.size(16.dp))
                }
            }
        }
    )
}