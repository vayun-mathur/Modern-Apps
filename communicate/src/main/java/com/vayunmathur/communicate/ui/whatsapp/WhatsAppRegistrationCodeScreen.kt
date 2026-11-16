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
 * Second WhatsApp registration step (`Route.WhatsAppRegistration.Code`):
 * enter the OTP for the number carried in the route. A successful verify
 * goes straight to Done; a `security_code` response pushes the conditional
 * PIN step instead — PIN is not a mandatory stack step.
 */
@Composable
fun WhatsAppRegistrationCodeScreen(
    cc: String,
    number: String,
    onBack: () -> Unit,
    onPinRequired: (cc: String, number: String) -> Unit,
    onVerified: (cc: String, number: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { RegistrationHttpClient(context.applicationContext) }
    val session = remember { WhatsAppLineSession.get(context) }

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

    WhatsAppRegistrationStepScaffold(busy = busy, status = status, onBack = onBack) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit) },
            label = { Text("6-digit code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && code.length >= 6,
            onClick = {
                run {
                    val r = withContext(Dispatchers.IO) { client.register(cc, number, code) }
                    status = "register: ${r.status}${r.reason?.let { " ($it)" } ?: ""}${r.param?.let { " param=$it" } ?: ""} raw=${r.raw.take(800)}"
                    when {
                        r.ok && r.auth != null -> {
                            session.markRegistered(context, r.auth)
                            onVerified(cc, number)
                        }
                        r.status == "security_code" -> onPinRequired(cc, number)
                    }
                }
            },
        ) { Text("Verify") }
    }
}
