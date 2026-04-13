package com.vayunmathur.contacts.ui.dialogs
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
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.R

@Composable
fun AddAccountDialog(
    viewModel: ContactViewModel,
    onDismiss: () -> Unit
) {
    var accountName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_contacts_account)) },
        text = {
            Column {
                Text(stringResource(R.string.enter_account_name))
                TextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.account_name)) }
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
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
