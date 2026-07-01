package com.vayunmathur.messages.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.R
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.telegram.TelegramClient
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramLoginScreen(backStack: NavBackStack<Route>) {
    val state by TelegramClient.state.collectAsState()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is TelegramClient.State.Connected) backStack.pop()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_telegram_title)) },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val s = state) {
                is TelegramClient.State.Idle,
                is TelegramClient.State.NeedsSetup -> {
                    Text(
                        stringResource(R.string.setup_telegram_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Primary: QR-code sign-in (scan from Telegram on your phone).
                    Button(
                        onClick = { TelegramClient.startQrLogin() },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text("Sign in with QR code", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f))
                        Text(
                            "or use your phone number",
                            modifier = Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text(stringResource(R.string.setup_telegram_phone_label)) },
                        placeholder = { Text(stringResource(R.string.setup_telegram_phone_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { TelegramClient.submitPhoneNumber(phone.trim()) },
                        enabled = phone.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(stringResource(R.string.setup_telegram_send_code), fontWeight = FontWeight.SemiBold)
                    }
                }

                is TelegramClient.State.Connecting -> {
                    Text(
                        stringResource(R.string.setup_telegram_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.height(40.dp),
                        strokeWidth = 3.dp,
                    )
                }

                is TelegramClient.State.AwaitingCode -> {
                    Text(
                        stringResource(R.string.setup_telegram_code_sent, s.phone),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.setup_telegram_code_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { TelegramClient.submitCode(code.trim()) },
                        enabled = code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(stringResource(R.string.setup_telegram_verify), fontWeight = FontWeight.SemiBold)
                    }
                }

                is TelegramClient.State.AwaitingPassword -> {
                    Text(
                        stringResource(R.string.setup_telegram_2fa_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.hint.isNotBlank()) {
                        Text(
                            stringResource(R.string.setup_telegram_2fa_hint, s.hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.setup_telegram_password_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { TelegramClient.submitPassword(password) },
                        enabled = password.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text(stringResource(R.string.setup_telegram_sign_in), fontWeight = FontWeight.SemiBold)
                    }
                }

                is TelegramClient.State.Connected -> {
                    Text("Connected to Telegram!")
                }

                // QR pairing: scan tg://login?token=… from Telegram on the
                // user's phone. The token auto-refreshes — each refresh emits
                // a new AwaitingQrScan(qrUrl), so the QR below re-renders.
                is TelegramClient.State.AwaitingQrScan -> {
                    Text(
                        "Open Telegram on your phone → Settings → Devices → " +
                            "Link Desktop Device, then scan this code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TelegramQrCard(s.qrUrl)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            "Waiting for you to scan…",
                            modifier = Modifier.padding(start = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { TelegramClient.start() }) {
                        Text("Use phone number instead")
                    }
                }

                is TelegramClient.State.Disconnected -> {
                    Text(
                        stringResource(R.string.setup_telegram_disconnected, s.reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { TelegramClient.start() },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text("Retry", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TelegramQrCard(url: String) {
    // QR generation for a 768² matrix is mildly expensive — hop off the
    // main thread so the frame isn't dropped. Recomputes on token refresh.
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.Default) { renderTelegramQr(url, sizePx = 768) }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
    ) {
        Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            val bm = bitmap
            if (bm == null) {
                CircularProgressIndicator()
            } else {
                Image(
                    bitmap = bm.asImageBitmap(),
                    contentDescription = "Telegram QR code",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun renderTelegramQr(content: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        // High EC so the QR survives glare / camera tilt during the scan.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val row = y * sizePx
        for (x in 0 until sizePx) {
            pixels[row + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}
