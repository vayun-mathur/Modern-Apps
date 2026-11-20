package com.vayunmathur.securemail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRemoveCircle
import com.vayunmathur.library.ui.InputChip
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.securemail.R

/**
 * Recipient field (shared by To/Cc/Bcc): chips above a single-line input.
 * Type an address + Enter (Done) to add it as a chip; tap a chip's ✕ to
 * remove; the trailing `+` opens the contacts picker. Invalid addresses
 * surface [onInvalid] (messenger) instead of adding. No card wrapper — the
 * field label is the destination (To/Cc/Bcc) and the hint stays put.
 */
@Composable
fun RecipientChipField(
    label: String,
    recipients: List<String>,
    onAdd: (String) -> Boolean,
    onRemove: (String) -> Unit,
    onPickContact: () -> Unit,
    onInvalid: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (onAdd(clean)) {
            text = ""
        } else {
            onInvalid()
        }
    }
    if (recipients.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recipients.forEach { address ->
                InputChip(
                    selected = false,
                    onClick = { onRemove(address) },
                    label = { Text(address) },
                    trailingIcon = { IconRemoveCircle() },
                )
            }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.recipient_type_address)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = { IconButton(onClick = onPickContact) { IconAdd() } },
    )
}
