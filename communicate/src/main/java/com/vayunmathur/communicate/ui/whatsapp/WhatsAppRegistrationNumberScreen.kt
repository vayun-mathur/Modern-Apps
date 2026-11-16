package com.vayunmathur.communicate.ui.whatsapp

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
import com.vayunmathur.communicate.data.whatsapp.registration.RegistrationHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First WhatsApp registration step (`Route.WhatsAppRegistration.Number`):
 * enter the number and request an OTP. On success the host pushes the Code
 * step carrying the entered country code and number.
 */
@Composable
fun WhatsAppRegistrationNumberScreen(
    onBack: () -> Unit,
    onCodeRequested: (cc: String, number: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { RegistrationHttpClient(context.applicationContext) }

    var cc by remember { mutableStateOf("1") }
    var number by remember { mutableStateOf("") }
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
                        val r = withContext(Dispatchers.IO) { client.requestCode(cc, number, "sms") }
                        status = "code: ${r.status}${r.reason?.let { " ($it)" } ?: ""}${r.param?.let { " param=$it" } ?: ""} raw=${r.raw.take(800)}"
                        if (r.ok) onCodeRequested(cc, number)
                    }
                },
            ) { Text("Request SMS code") }
            OutlinedButton(
                enabled = !busy && number.isNotBlank(),
                onClick = {
                    run {
                        val r = withContext(Dispatchers.IO) { client.requestCode(cc, number, "voice") }
                        status = "code: ${r.status}${r.reason?.let { " ($it)" } ?: ""}${r.param?.let { " param=$it" } ?: ""} raw=${r.raw.take(800)}"
                        if (r.ok) onCodeRequested(cc, number)
                    }
                },
            ) { Text("Voice") }
        }
        // Validates the param set against /v2/exist WITHOUT sending an SMS (debug aid).
        OutlinedButton(
            enabled = !busy && number.isNotBlank(),
            onClick = {
                run {
                    val r = withContext(Dispatchers.IO) { client.checkExist(cc, number) }
                    status = "exist: ${r.status}${r.reason?.let { " ($it)" } ?: ""}${r.param?.let { " param=$it" } ?: ""} raw=${r.raw.take(800)}"
                }
            },
        ) { Text("Check number (no SMS)") }
    }
}
