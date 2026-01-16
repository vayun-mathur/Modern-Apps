package com.vayunmathur.passwords.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.passwords.Password
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordPage(backStack: NavBackStack<Route>, id: Long, viewModel: DatabaseViewModel) {
    val password by viewModel.get<Password>(id)
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(password.name.ifBlank { "Password" }) },
                navigationIcon = {
                    IconNavigation{ backStack.removeLastOrNull() }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { backStack.add(Route.PasswordEditPage(id)) }) {
                IconEdit()
            }
        },
    ) { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val initial = password.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initial, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Text(password.name.ifBlank { "(no name)" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(password.userId.ifBlank { "(no user)" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Password
            Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Password", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (showPassword) password.password else password.password.replace(Regex("."), "•"),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(onClick = { showPassword = !showPassword }) {
                            if (showPassword) Icon(painterResource(R.drawable.visibility_off_24px), contentDescription = "Hide")
                            else Icon(painterResource(R.drawable.visibility_24px), contentDescription = "Show")
                        }

                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("password", password.password))
                        }) {
                            Icon(painterResource(R.drawable.content_copy_24px), contentDescription = "Copy")
                        }
                    }
                }
            }

            // TOTP
            Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("TOTP Secret", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(password.totpSecret ?: "(not configured)", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Websites
            Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Websites", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (password.websites.isEmpty()) {
                        Text("(none)")
                    } else {
                        for (w in password.websites) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                                .fillMaxWidth()
                                .clickable { /* open link if desired */ }
                                .padding(vertical = 6.dp)) {
                                Text(w, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Icon(painterResource(R.drawable.link_24px), contentDescription = "Open site")
                            }
                        }
                    }
                }
            }
        }
    }
}