package com.vayunmathur.networklocation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.networklocation.OfflineDatabaseSection

/** First-run screen: what the app does, and the offline-database download it cannot work without. */
@Composable
fun SetupScreen(onReady: () -> Unit) {
    AppScaffold(title = "Network Location", scrollBehavior = appBarScrollBehavior()) { padding ->
        // Letterboxed on expanded windows so the status text keeps a readable measure.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                Text(
                    "Provides the system network-location provider and an on-device, offline " +
                        "geocoder. Both resolve entirely on device from the downloaded databases " +
                        "below — nothing is sent to a positioning service. Always on — there is no " +
                        "disable switch.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OfflineDatabaseSection(Modifier.padding(top = 24.dp), onReady = onReady)
            }
        }
    }
}
