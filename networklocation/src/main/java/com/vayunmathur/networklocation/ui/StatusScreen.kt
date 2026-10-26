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
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.networklocation.OfflineDatabaseSection

/** Home once the databases are installed: the live GPS / network-location / geocoder check. */
@Composable
fun StatusScreen() {
    AppScaffold(title = "Network Location", scrollBehavior = appBarScrollBehavior()) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                LiveLocationSection()
                OfflineDatabaseSection(Modifier.padding(top = 24.dp))
            }
        }
    }
}
