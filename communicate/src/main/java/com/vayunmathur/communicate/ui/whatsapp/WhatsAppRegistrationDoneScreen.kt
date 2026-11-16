package com.vayunmathur.communicate.ui.whatsapp

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * Final WhatsApp registration step (`Route.WhatsAppRegistration.Done`).
 * The host replaces the whole registration sub-stack with this
 * destination, so Done and Back both leave registration entirely —
 * exactly as the single-screen flow's buttons did.
 */
@Composable
fun WhatsAppRegistrationDoneScreen(
    onBack: () -> Unit,
    onRegistered: () -> Unit,
) {
    WhatsAppRegistrationStepScaffold(busy = false, status = null, onBack = onBack) {
        Text("Registered ✓  WhatsApp primary line is active.")
        Button(onClick = onRegistered) { Text("Done") }
    }
}
