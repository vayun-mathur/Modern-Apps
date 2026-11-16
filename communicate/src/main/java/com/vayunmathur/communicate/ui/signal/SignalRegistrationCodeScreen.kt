package com.vayunmathur.communicate.ui.signal

import androidx.compose.foundation.layout.fillMaxWidth
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
import com.vayunmathur.communicate.data.signal.SignalLineSession
import com.vayunmathur.communicate.data.signal.registration.RegistrationHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Signal registration code step (`Route.SignalRegistration.Code`): enter
 * the OTP for the number carried in the route. Back and "Change number"
 * both return to Number — when a captcha was solved the host replaces
 * Captcha with this step, so neither path lands back on the captcha.
 */
@Composable
fun SignalRegistrationCodeScreen(
    cc: String,
    number: String,
    onBack: () -> Unit,
    onVerified: (cc: String, number: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { RegistrationHttpClient(context.applicationContext) }
    val session = remember { SignalLineSession.get(context) }

    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

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
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit) },
            label = { Text("6-digit code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && code.length >= 4,
            onClick = {
                run {
                    val r = withContext(Dispatchers.IO) { client.verifyCode(code) }
                    status = "register: ${r.status}${r.reason?.let { " ($it)" } ?: ""}"
                    if (r.ok && r.auth != null) {
                        session.markRegistered(context, r.auth)
                        onVerified(cc, number)
                    }
                }
            },
        ) { Text("Verify") }
        OutlinedButton(
            enabled = !busy,
            onClick = onBack,
        ) { Text("Change number") }
    }
}
