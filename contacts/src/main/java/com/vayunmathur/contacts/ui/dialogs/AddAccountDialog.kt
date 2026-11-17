package com.vayunmathur.contacts.ui.dialogs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.platform.createAccount
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
                Text(stringResource(UiR.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.cancel))
            }
        }
    )
}
