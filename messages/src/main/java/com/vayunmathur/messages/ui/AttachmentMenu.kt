package com.vayunmathur.messages.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.IconCamera
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconNavigationArrow
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconUpload
import com.vayunmathur.messages.util.FindFamilyLocation
import com.vayunmathur.messages.util.MediaCapability

/**
 * Bottom sheet listing the composer attachment actions supported by the
 * current conversation's platform. Only entries whose [MediaCapability]
 * is in [capabilities] are shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(
    capabilities: Set<MediaCapability>,
    onPhoto: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onPoll: () -> Unit,
    onLocation: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            if (MediaCapability.IMAGE in capabilities) {
                AttachmentRow("Photo", { IconShare() }, onPhoto)
                AttachmentRow("Take photo", { IconCamera() }, onCamera)
            }
            if (MediaCapability.FILE in capabilities) {
                AttachmentRow("File", { IconUpload() }, onFile)
            }
            if (MediaCapability.POLL in capabilities) {
                AttachmentRow("Poll", { IconCheck() }, onPoll)
            }
            if (MediaCapability.LOCATION in capabilities) {
                AttachmentRow("Location", { IconNavigationArrow() }, onLocation)
            }
        }
    }
}

@Composable
private fun AttachmentRow(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(label, modifier = Modifier.padding(start = 20.dp), fontSize = 16.sp)
    }
}

/**
 * Picks a name + active-duration for a FindFamily location-sharing link.
 * [onConfirm] fires with the chosen name and the duration in milliseconds.
 */
@Composable
fun LocationDurationDialog(
    defaultName: String,
    onConfirm: (name: String, expiryMillis: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var selected by remember { mutableStateOf(FindFamilyLocation.DURATION_OPTIONS[2]) } // 1 hour

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share location") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Link name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Active for",
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    FindFamilyLocation.DURATION_OPTIONS.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option == selected,
                                    onClick = { selected = option },
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option == selected,
                                onClick = { selected = option },
                            )
                            Text(option.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(name.trim().ifBlank { defaultName }, selected.millis)
                },
            ) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
