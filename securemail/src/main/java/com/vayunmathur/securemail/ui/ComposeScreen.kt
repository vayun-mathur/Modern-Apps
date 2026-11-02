package com.vayunmathur.securemail.ui

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.securemail.platform.MailUiState
import kotlinx.coroutines.launch

/**
 * Compose: no top app bar (full-bleed form); recipients as Enter-to-add
 * chips (To/Cc/Bcc) with a contacts-picker `+`; subject single-line
 * (`Next`), body multi-line (`Done`). Draft recipients live in the ViewModel
 * so picker navigation doesn't wipe them.
 */
@Composable
fun ComposeScreen(
    state: MailUiState,
    draftTo: List<String>,
    draftCc: List<String>,
    draftBcc: List<String>,
    initialSubject: String,
    initialBody: String,
    onAddRecipient: (Int, String) -> Boolean,
    onRemoveRecipient: (Int, String) -> Unit,
    onSend: (List<String>, List<String>, List<String>, String, String, String?) -> Unit,
    onLeave: (subject: String, body: String) -> Unit,
    onDiscard: () -> Unit,
    onConsumeMessage: () -> Unit = {},
) {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    ConsumeMessage(state.message, messenger, onConsumeMessage)
    var subject by remember { mutableStateOf(initialSubject) }
    var body by remember { mutableStateOf(initialBody) }
    val scope = rememberCoroutineScope()
    var pickTarget by remember { mutableStateOf(0) }
    var pickTick by remember { mutableStateOf(0) }
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val email = result.data?.data?.let { contactEmail(context, it) }
            if (!email.isNullOrBlank()) {
                if (!onAddRecipient(pickTarget, email)) {
                    scope.launch { messenger.show("Invalid or duplicate address.") }
                }
            }
        }
    }
    LaunchedEffect(pickTick) {
        if (pickTick > 0) {
            contactPicker.launch(
                Intent(
                    Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                ),
            )
        }
    }
    val allRecipients = draftTo + draftCc + draftBcc
    // Back saves the composition as a draft (handled by the caller); Discard drops it.
    BackHandler { onLeave(subject, body) }
    // No top app bar by design; systemBarsPadding keeps the form clear of
    // the status bar / camera cutout, imePadding keeps Send above the keyboard.
    DesktopMaxWidthContainer(
        Modifier.fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            RecipientChipField(
                label = "To",
                recipients = draftTo,
                onAdd = { onAddRecipient(0, it) },
                onRemove = { onRemoveRecipient(0, it) },
                onPickContact = { pickTarget = 0; pickTick++ },
                onInvalid = { scope.launch { messenger.show("Enter a valid email address.") } },
            )
            RecipientChipField(
                label = "Cc",
                recipients = draftCc,
                onAdd = { onAddRecipient(1, it) },
                onRemove = { onRemoveRecipient(1, it) },
                onPickContact = { pickTarget = 1; pickTick++ },
                onInvalid = { scope.launch { messenger.show("Enter a valid email address.") } },
            )
            RecipientChipField(
                label = "Bcc",
                recipients = draftBcc,
                onAdd = { onAddRecipient(2, it) },
                onRemove = { onRemoveRecipient(2, it) },
                onPickContact = { pickTarget = 2; pickTick++ },
                onInvalid = { scope.launch { messenger.show("Enter a valid email address.") } },
            )
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Subject") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { /* keep focus; Send submits */ }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            onSend(draftTo, draftCc, draftBcc, subject, body, null)
                        }
                    },
                    enabled = !state.busy && allRecipients.isNotEmpty() && body.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.busy) "Sending…" else "Send") }
                Button(onClick = onDiscard, modifier = Modifier.weight(1f)) { Text("Discard") }
            }
        }
    }
}

/** Reads the email address from a contact-picker result URI (per-item grant, no permission needed). */
internal fun contactEmail(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
