package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.network.uber.UberAuth
import com.vayunmathur.taxi.network.uber.UberAuthResult
import kotlinx.coroutines.launch

/**
 * Native Uber sign-in driving the silkscreen form machine directly (no WebView).
 *
 * Whether this can complete depends on whether Uber's server enforces the `deviceData`
 * fingerprint that `libse_loader.so` normally supplies; we cannot produce it. [onUseWebView]
 * is the fallback for when it doesn't.
 */
@Composable
fun UberSignInScreen(onBack: () -> Unit, onUseWebView: () -> Unit) {
    val scope = rememberCoroutineScope()

    var countryCode by remember { mutableStateOf("1") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var screenType by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun handle(result: UberAuthResult) {
        busy = false
        when (result) {
            is UberAuthResult.NextScreen -> {
                error = null
                sessionId = result.sessionId
                screenType = result.screenType
            }
            is UberAuthResult.Failed -> error = result.message
        }
    }

    AppScaffold(title = stringResource(R.string.provider_uber), onNavigateBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = countryCode,
                onValueChange = { countryCode = it },
                label = { Text(stringResource(R.string.country_code)) },
                singleLine = true,
                enabled = sessionId == null && !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text(stringResource(R.string.phone_number)) },
                placeholder = { Text("2135551234") },
                singleLine = true,
                enabled = sessionId == null && !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            if (sessionId != null) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.verification_code)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val current = sessionId
                        handle(
                            if (current == null) {
                                UberAuth.startPhoneLogin(countryCode.trim(), phone.trim())
                            } else {
                                UberAuth.submitSmsOtp(current, code.trim())
                            },
                        )
                    }
                },
                enabled = !busy && phone.isNotBlank() && (sessionId == null || code.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(if (sessionId == null) R.string.send_code else R.string.verify),
                )
            }

            screenType?.let { Text(stringResource(R.string.uber_next_screen, it)) }
            error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }

            OutlinedButton(onClick = onUseWebView, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.uber_use_webview))
            }
        }
    }
}
