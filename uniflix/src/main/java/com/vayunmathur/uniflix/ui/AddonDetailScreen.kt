package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.uniflix.platform.AddonsViewModel

/**
 * Per-addon detail: manifest identity, health, enable/default toggles, test +
 * refresh actions, and the opaque per-addon settings JSON.
 */
@Composable
fun AddonDetailScreen(addonDbId: Long, onBack: () -> Unit) {
    val vm: AddonsViewModel = viewModel()
    val rows by vm.addons.collectAsState()
    val row = rows.firstOrNull { it.entity.dbId == addonDbId }

    AppScaffold(
        title = row?.entity?.name ?: "Addon",
        onNavigateBack = onBack,
    ) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            if (row == null) {
                Text("Addon not found.", modifier = Modifier.padding(16.dp))
                return@DesktopMaxWidthContainer
            }
            val entity = row.entity
            var configDraft by remember(entity.configJson) { mutableStateOf(entity.configJson) }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Manifest: ${entity.manifestUrl}", style = MaterialTheme.typography.bodySmall)
                if (entity.version.isNotBlank()) {
                    Text("Version: ${entity.version}")
                }
                entity.lastError?.let { Text("Last error: $it") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Enabled")
                    Switch(checked = entity.enabled, onCheckedChange = { vm.setEnabled(row, !entity.enabled) })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Default addon")
                    Switch(
                        checked = row.isDefault,
                        onCheckedChange = { if (row.isDefault) vm.clearDefault() else vm.setDefault(row) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.testAddon(row) }) { Text("Test") }
                    OutlinedButton(onClick = { vm.refreshManifest(row) }) { Text("Refresh manifest") }
                }
                Text("Addon settings (JSON)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = configDraft,
                    onValueChange = { configDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Button(
                    onClick = { vm.updateConfig(entity.dbId, configDraft) },
                    enabled = configDraft != entity.configJson,
                ) { Text("Save settings") }
                OutlinedButton(onClick = { vm.remove(row); onBack() }) { Text("Remove addon") }
            }
        }
    }
}
