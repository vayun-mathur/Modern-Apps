package com.vayunmathur.communicate.ui.signal

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.data.signal.registration.RegistrationHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Conditional Signal registration step (`Route.SignalRegistration.Captcha`):
 * only pushed when requesting a code answers with a captcha demand, so
 * Back/Cancel returns to Number and numbers that are not challenged never
 * see it. The transport to resume is carried in the route.
 */
@Composable
fun SignalRegistrationCaptchaScreen(
    cc: String,
    number: String,
    transport: String,
    onBack: () -> Unit,
    onCodeRequested: (cc: String, number: String) -> Unit,
    onCaptchaRejected: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { RegistrationHttpClient(context.applicationContext) }

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
        Text(
            "Signal requires a captcha to continue. Complete the challenge below.",
            style = MaterialTheme.typography.bodySmall,
        )
        SignalCaptchaWebView(
            onToken = { token ->
                run {
                    val r = withContext(Dispatchers.IO) {
                        client.submitCaptcha(e164(), token, transport)
                    }
                    status = "code: ${r.status}${r.reason?.let { " ($it)" } ?: ""}"
                    when {
                        r.ok -> onCodeRequested(cc, number)
                        !r.needsCaptcha -> onCaptchaRejected()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(420.dp),
        )
        OutlinedButton(
            enabled = !busy,
            onClick = onBack,
        ) { Text("Cancel") }
    }
}
