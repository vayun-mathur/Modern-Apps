package com.vayunmathur.communicate.ui.signal

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior

/**
 * Chrome shared by every Signal registration step destination: the
 * primary-device warning, the busy/status footer and the Back button.
 * Which step is showing is decided by the navigation stack, not here.
 */
@Composable
internal fun SignalRegistrationStepScaffold(
    busy: Boolean,
    status: String?,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DetailScaffold(title = "Register Signal") {
        Text(
            "Registers this number as the PRIMARY Signal device. This will link Signal to this app — " +
                "use a test number.",
            style = MaterialTheme.typography.bodySmall,
        )
        content()
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Working…")
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}
