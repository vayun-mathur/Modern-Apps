package com.vayunmathur.backup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.backup.platform.BackupUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.backup.ui.components.BackendSection
import com.vayunmathur.library.ui.Text

private enum class SetupMode { NEW, RESTORE }

/** First-run setup: choose a destination and set up or restore the 12-word recovery code. */
@Composable
fun OnboardingScreen(
    state: BackupUiState,
    onPickFolder: () -> Unit,
    onSetWebDav: (String, String, String) -> Unit,
    onGenerate: () -> Unit,
    onConfirmNew: (List<String>) -> Unit,
    onRestoreWithCode: (List<String>) -> Unit,
    onDismissMessages: () -> Unit,
) {
    AppScaffold(title = "Set up Backup") { padding ->
        // Letterboxed on expanded windows so the form keeps a readable measure.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            Text(
                "Your backups are encrypted with a 12-word recovery code. Write it down — it is the " +
                    "only way to restore on a new device.",
                style = MaterialTheme.typography.bodyMedium,
            )

            BackendSection(state.settings, onPickFolder, onSetWebDav)

            RecoveryCodeCard(
                generatedCode = state.generatedCode,
                onGenerate = onGenerate,
                onConfirmNew = onConfirmNew,
                onRestoreWithCode = onRestoreWithCode,
                onInteract = onDismissMessages,
            )
            }
        }
    }
}

@Composable
private fun RecoveryCodeCard(
    generatedCode: List<String>,
    onGenerate: () -> Unit,
    onConfirmNew: (List<String>) -> Unit,
    onRestoreWithCode: (List<String>) -> Unit,
    onInteract: () -> Unit,
) {
    var mode by remember { mutableStateOf(SetupMode.NEW) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Recovery code", style = MaterialTheme.typography.titleMedium)

            Row(modifier = Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = mode == SetupMode.NEW,
                    onClick = { mode = SetupMode.NEW },
                    label = { Text("New backup") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = mode == SetupMode.RESTORE,
                    onClick = { mode = SetupMode.RESTORE },
                    label = { Text("Restore existing") },
                )
            }

            when (mode) {
                SetupMode.NEW -> NewCodeSection(generatedCode, onGenerate, onConfirmNew, onInteract)
                SetupMode.RESTORE -> RestoreCodeSection(onRestoreWithCode, onInteract)
            }
        }
    }
}

@Composable
private fun NewCodeSection(
    generatedCode: List<String>,
    onGenerate: () -> Unit,
    onConfirmNew: (List<String>) -> Unit,
    onInteract: () -> Unit,
) {
    if (generatedCode.isEmpty()) {
        Button(
            onClick = { onInteract(); onGenerate() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Generate recovery code") }
        return
    }
    val numbered = generatedCode.mapIndexed { i, w -> "${i + 1}. $w" }.joinToString("\n")
    Text(
        numbered,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onConfirmNew(generatedCode) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("I saved it — enable encrypted backups") }
}

@Composable
private fun RestoreCodeSection(
    onRestoreWithCode: (List<String>) -> Unit,
    onInteract: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onInteract() },
        label = { Text("Enter your 12-word recovery code") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
    )
    Button(
        onClick = { onRestoreWithCode(text.trim().split(Regex("\\s+"))) },
        enabled = text.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Restore with recovery code") }
}
