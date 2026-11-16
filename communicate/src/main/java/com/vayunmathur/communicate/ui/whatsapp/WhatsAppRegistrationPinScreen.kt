package com.vayunmathur.communicate.ui.whatsapp

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import com.vayunmathur.communicate.data.whatsapp.WhatsAppLineSession
import com.vayunmathur.communicate.data.whatsapp.registration.RegistrationHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Conditional WhatsApp registration step (`Route.WhatsAppRegistration.Pin`):
 * only pushed when the Code step's verify answers `security_code`, so Back
 * from here returns to Code and numbers without two-step verification
 * never see it.
 */
@Composable
fun WhatsAppRegistrationPinScreen(
    cc: String,
    number: String,
    onBack: () -> Unit,
    onVerified: (cc: String, number: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { RegistrationHttpClient(context.applicationContext) }
    val session = remember { WhatsAppLineSession.get(context) }

    var pin by remember { mutableStateOf("") }
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

    WhatsAppRegistrationStepScaffold(busy = busy, status = status, onBack = onBack) {
        Text("This number has two-step verification. Enter the PIN.")
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit) },
            label = { Text("2FA PIN") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && pin.length >= 6,
            onClick = {
                run {
                    val r = withContext(Dispatchers.IO) { client.submitTwoFactor(cc, number, pin) }
                    status = "2fa: ${r.status}${r.reason?.let { " ($it)" } ?: ""}"
                    if (r.ok && r.auth != null) {
                        session.markRegistered(context, r.auth)
                        onVerified(cc, number)
                    }
                }
            },
        ) { Text("Submit PIN") }
    }
}
