package com.vayunmathur.contacts.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vayunmathur.contacts.ContactViewModel

@Composable
fun AddAccountDialog(
    viewModel: ContactViewModel,
    onDismiss: () -> Unit
) {
    var accountName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Contacts Account") },
        text = {
            Column {
                Text("Enter a name for the new local account:")
                TextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Account Name") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (accountName.isNotBlank()) {
                        viewModel.createAccount(accountName)
                        onDismiss()
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
