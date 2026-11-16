package com.vayunmathur.communicate.ui.signal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.data.signal.registration.RegistrationHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First Signal registration step (`Route.SignalRegistration.Number`):
 * enter the number and request an OTP. The host pushes the conditional
 * Captcha step when the server demands one, otherwise the Code step —
 * both carrying the entered country code and number.
 */
@Composable
fun SignalRegistrationNumberScreen(
    onBack: () -> Unit,
    onCaptchaRequired: (cc: String, number: String, transport: String) -> Unit,
    onCodeRequested: (cc: String, number: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { RegistrationHttpClient(context.applicationContext) }

    var cc by remember { mutableStateOf("1") }
    var number by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun e164(): String = "+${cc.filter { it.isDigit() }}${number.filter { it.isDigit() }}"

    fun run(block: suspend () -> Unit) {
        busy = true
        status = null
        scope.launch {
            try {
                block()
            } catch (expected: Throwable) {
                status = "Error: ${expected.message}"
            } finally {
                busy = false
            }
        }
    }

    SignalRegistrationStepScaffold(busy = busy, status = status, onBack = onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = cc,
                onValueChange = { cc = it.filter(Char::isDigit) },
                label = { Text("CC") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.width(90.dp),
            )
            OutlinedTextField(
                value = number,
                onValueChange = { number = it.filter(Char::isDigit) },
                label = { Text("Phone number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && number.isNotBlank(),
                onClick = {
                    run {
                        val r = withContext(Dispatchers.IO) { client.requestSmsCode(e164()) }
                        status = "code: ${r.status}${r.reason?.let { " ($it)" } ?: ""}"
                        when {
                            r.needsCaptcha -> onCaptchaRequired(cc, number, "sms")
                            r.ok -> onCodeRequested(cc, number)
                        }
                    }
                },
            ) { Text("Request SMS code") }
            OutlinedButton(
                enabled = !busy && number.isNotBlank(),
                onClick = {
                    run {
                        val r = withContext(Dispatchers.IO) { client.requestVoiceCode(e164()) }
                        status = "code: ${r.status}${r.reason?.let { " ($it)" } ?: ""}"
                        when {
                            r.needsCaptcha -> onCaptchaRequired(cc, number, "voice")
                            r.ok -> onCodeRequested(cc, number)
                        }
                    }
                },
            ) { Text("Voice") }
        }
        OutlinedButton(
            enabled = !busy && number.isNotBlank(),
            onClick = {
                run {
                    val r = withContext(Dispatchers.IO) { client.checkExists(e164()) }
                    status = "exists: ${r.exists}${r.reason?.let { " ($it)" } ?: ""}"
                }
            },
        ) { Text("Check number (no SMS)") }
    }
}
