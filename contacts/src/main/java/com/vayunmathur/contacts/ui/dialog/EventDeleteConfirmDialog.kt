package com.vayunmathur.contacts.ui.dialog

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.vayunmathur.contacts.ContactViewModel
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
        onDismissRequest = { onDismiss() },
        title = { Text(text = "Delete contact") },
        text = {
            Text(
                text = "Are you sure you want to delete ${contactName ?: "this contact"}? This action cannot be undone."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                // Perform deletion directly from the dialog using the provided ViewModel
                scope.launch(Dispatchers.IO) {
                    viewModel.getContact(contactId)?.let { contact ->
                        viewModel.deleteContact(contact)
                    }
                }
                onConfirm()
            }) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Cancel")
            }
        }
    )
}
