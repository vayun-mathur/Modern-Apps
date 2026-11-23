package com.vayunmathur.uniflix.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/** Add-by-URL dialog: accepts a bare addon base or a full `.../manifest.json` URL. */
@Composable
fun AddAddonDialog(
    adding: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onImportJs: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add stream addon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste the addon manifest URL, e.g. https://example.com/manifest.json")
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Addon URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                TextButton(onClick = onImportJs, enabled = !adding) {
                    Text("Import .js plugin instead")
                }
                if (adding) Text("Loading\u2026")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url) }, enabled = !adding && url.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !adding) { Text("Cancel") }
        },
    )
}
