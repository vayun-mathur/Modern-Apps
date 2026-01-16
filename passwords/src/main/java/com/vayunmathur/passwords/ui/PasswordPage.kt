package com.vayunmathur.passwords.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.CircularProgressIndicator
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
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.passwords.Password
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.R
import androidx.core.net.toUri
import com.vayunmathur.library.ui.IconNavigation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordPage(backStack: NavBackStack<Route>, id: Long, viewModel: DatabaseViewModel) {
    val password by viewModel.get<Password>(id)
    val context = LocalContext.current
    var showPassword by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()


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
                                currentCode = generateTotp(password.totpSecret!!, timeStep*30)

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
                                Text("Refreshes in ${secondsRemaining} s", style = MaterialTheme.typography.bodySmall)
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
                                    val intent = Intent(Intent.ACTION_VIEW, w.toUri())
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

// TOTP implementation (RFC 6238) using HMAC-SHA1 and 6 digits
private fun base32Decode(data: String): ByteArray {
    val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val clean = data.trim().replace("=", "").replace(" ", "").uppercase()
    val output = mutableListOf<Byte>()
    var buffer = 0
    var bitsLeft = 0
    for (c in clean) {
        val valC = base32Chars.indexOf(c)
        if (valC == -1) continue
        buffer = (buffer shl 5) or valC
        bitsLeft += 5
        if (bitsLeft >= 8) {
            bitsLeft -= 8
            output.add(((buffer shr bitsLeft) and 0xFF).toByte())
        }
    }
    return output.toByteArray()
}

private fun hotp(key: ByteArray, counter: Long, digits: Int = 6): String {
    val counterBytes = ByteArray(8)
    var c = counter
    for (i in 7 downTo 0) {
        counterBytes[i] = (c and 0xFF).toByte()
        c = c ushr 8
    }
    val mac = Mac.getInstance("HmacSHA1")
    val keySpec = SecretKeySpec(key, "RAW")
    mac.init(keySpec)
    val hash = mac.doFinal(counterBytes)
    val offset = (hash[hash.size - 1].toInt() and 0x0f)
    val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
    val otp = binary % Math.pow(10.0, digits.toDouble()).toInt()
    return otp.toString().padStart(digits, '0')
}

private fun generateTotp(secret: String, epochSecond: Long): String {
    val key = base32Decode(secret)
    val timeStep = 30L
    val counter = epochSecond / timeStep
    return hotp(key, counter, 6)
}
