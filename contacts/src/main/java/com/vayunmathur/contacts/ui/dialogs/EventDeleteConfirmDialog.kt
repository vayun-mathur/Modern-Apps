package com.vayunmathur.contacts.ui.dialogs
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun EventDeleteConfirmDialog(
    contactId: Long,
    contactName: String? = null,
    viewModel: ContactViewModel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp),
        title = { Text(text = stringResource(R.string.delete_contact_title)) },
        text = {
            Text(
                text = if (contactName.isNullOrBlank()) {
                    stringResource(R.string.delete_contact_confirm)
                } else {
                    stringResource(R.string.delete_contact_name_confirm, contactName)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    viewModel.getContact(contactId)?.let { contact ->
                        viewModel.deleteContact(contact)
                    }
                }
                onConfirm()
            }) {
                Text(stringResource(UiR.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.cancel))
            }
        }
    )
}
