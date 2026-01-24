package com.vayunmathur.passwords.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.passwords.Password
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.TOTP
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordPage(backStack: NavBackStack<Route>, id: Long, viewModel: DatabaseViewModel) {
    val password by viewModel.get<Password>(id){Password()}
    if(password == Password()) return
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(password.name.ifBlank { "Password" }) },
                actions = {
                    IconButton(onClick = { viewModel.delete(password); backStack.removeLastOrNull() }) {
                        IconDelete()
                    }
                },
                navigationIcon = {
                    IconNavigation(backStack)
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { backStack.add(Route.PasswordEditPage(id)) }) {
                IconEdit()
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
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
                    // Avatar with initial
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

            // Password card
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

            // TOTP card: show generated code and circular timer
            Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("TOTP", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    if (password.totpSecret.isNullOrBlank()) {
                        Text("(not configured)")
                    } else {
                        var currentCode by remember { mutableStateOf("") }
                        var progress by remember { mutableFloatStateOf(1f) }
                        var secondsRemaining by remember { mutableIntStateOf(30) }

                        LaunchedEffect(Unit) {
                            while (true) {
                                val nowMs = System.currentTimeMillis()
                                val nowSeconds = nowMs / 1000

                                // 1. Calculate the current 30s bucket (Time Step)
                                val timeStep = nowSeconds / 30

                                // 2. Generate code based on this bucket
                                currentCode = TOTP.generate(password.totpSecret!!, timeStep*30)

                                // 3. Calculate remaining time in this specific bucket
                                val millisIntoStep = nowMs % 30000
                                val millisRemaining = 30000 - millisIntoStep

                                secondsRemaining = (millisRemaining / 1000).toInt()
                                progress = millisRemaining / 30000f

                                // Tick frequently for a smooth progress bar
                                delay(50)
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(currentCode, style = MaterialTheme.typography.displayMedium)
                                Spacer(Modifier.height(4.dp))
                                Text("Refreshes in $secondsRemaining s", style = MaterialTheme.typography.bodySmall)
                            }

                            // Circular progress showing proportion of time remaining
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator({ progress }, Modifier.size(56.dp))
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("totp", currentCode))
                                    scope.launch { snackbarHostState.showSnackbar("TOTP copied") }
                                }) {
                                    Icon(painterResource(R.drawable.content_copy_24px), contentDescription = "Copy TOTP")
                                }
                            }
                        }
                    }
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
                                .clickable {
                                    // open link
                                    val intent = Intent(Intent.ACTION_VIEW, sanitizeUrl(w).toUri())
                                    context.startActivity(intent)
                                }
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

fun sanitizeUrl(input: String): String {
    val trimmed = input.trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://$trimmed"
    }
}