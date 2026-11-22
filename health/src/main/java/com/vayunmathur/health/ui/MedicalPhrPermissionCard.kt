package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.health.R
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text

/**
 * The tap target for PHR access, following the gate-row pattern (Card + ListItem +
 * trailing Grant button) the shared permission gate uses: purpose text is read
 * first, and only the button invokes the Health Connect contract.
 */
@Composable
fun MedicalPhrPermissionCard(onRequest: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.phr_permission_title)) },
            supportingContent = { Text(stringResource(R.string.phr_permission_body)) },
            trailingContent = {
                Button(onClick = onRequest) {
                    Text(stringResource(R.string.phr_permission_action))
                }
            },
        )
    }
}
