package com.vayunmathur.communicate.ui.whatsapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.communicate.data.whatsapp.backup.BackupImporter
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Imports a local `msgstore.db.crypt15` using a 64-hex-char backup key. SAF file picker + key field
 * → [BackupImporter] → summary. Available whether or not the primary line is registered.
 */
@Composable
fun WhatsAppBackupImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var keyHex by remember { mutableStateOf("") }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var pickedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedName = uri.toString().substringAfterLast('/')
            scope.launch {
                pickedBytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                }
                status = if (pickedBytes != null) "Loaded ${pickedBytes!!.size} bytes" else "Failed to read file"
            }
        }
    }

    DetailScaffold(title = "Import WhatsApp backup") {
        Text("Select a msgstore.db.crypt15 file and enter its 64-character hex backup key.")

        OutlinedButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(pickedName ?: "Choose .crypt15 file") }

        OutlinedTextField(
            value = keyHex,
            onValueChange = { keyHex = it.trim().filter { c -> c.isLetterOrDigit() } },
            label = { Text("64-hex backup key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            enabled = !busy && pickedBytes != null && keyHex.length == 64,
            onClick = {
                busy = true
                status = "Importing…"
                scope.launch {
                    val key = runCatching { hexToBytes(keyHex) }.getOrNull()
                    if (key == null || key.size != 32) {
                        status = "Invalid key (need 64 hex chars = 32 bytes)"
                        busy = false
                        return@launch
                    }
                    val result = withContext(Dispatchers.IO) {
                        BackupImporter.import(context, pickedBytes!!, key)
                    }
                    status = if (result.errors.isEmpty()) {
                        "Imported ${result.messageCount} messages in ${result.conversationCount} chats"
                    } else {
                        "Errors: ${result.errors.joinToString("; ")}"
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import") }

        if (busy) CircularProgressIndicator()
        status?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

private fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd hex length" }
    return ByteArray(hex.length / 2) {
        ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
    }
}
