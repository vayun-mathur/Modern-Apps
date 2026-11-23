package com.vayunmathur.communicate.ui.whatsapp

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DetailScaffold

/**
 * Chrome shared by every WhatsApp registration step destination: the
 * primary-device warning, the busy/status footer and the Back button.
 * Which step is showing is decided by the navigation stack, not here.
 */
@Composable
internal fun WhatsAppRegistrationStepScaffold(
    busy: Boolean,
    status: String?,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DetailScaffold(title = "Register WhatsApp", isRefreshing = busy) {
        Text(
            "Registers this number as the PRIMARY WhatsApp device. This will log WhatsApp out " +
                "on the number's real phone — use a test number.",
            style = MaterialTheme.typography.bodySmall,
        )
        content()
        if (busy) {
            Text("Working…")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}
