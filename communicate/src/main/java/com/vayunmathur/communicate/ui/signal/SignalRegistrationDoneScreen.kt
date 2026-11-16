package com.vayunmathur.communicate.ui.signal

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Final Signal registration step (`Route.SignalRegistration.Done`). The
 * host replaces the whole registration sub-stack with this destination,
 * so Done and Back both leave registration entirely — exactly as the
 * single-screen flow's buttons did.
 */
@Composable
fun SignalRegistrationDoneScreen(
    onBack: () -> Unit,
    onRegistered: () -> Unit,
) {
    SignalRegistrationStepScaffold(busy = false, status = null, onBack = onBack) {
        Text("Registered ✓  Signal primary line is active.")
        Button(onClick = onRegistered) { Text("Done") }
    }
}
