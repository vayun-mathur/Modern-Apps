package com.vayunmathur.contacts.ui.dialogs
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.platform.ContactAccount
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.platform.renameLocalAccount
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.util.AppMessages

@Composable
fun RenameAccountDialog(
    viewModel: ContactViewModel,
    target: ContactAccount,
    onDismiss: () -> Unit
) {
    val accountNameExistsMessage = stringResource(R.string.account_name_exists)
    var newName by remember(target) { mutableStateOf(target.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_account)) },
        text = {
            TextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.account_name)) }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val name = newName.trim()
                if (name.isNotEmpty()) {
                    viewModel.renameLocalAccount(target, name) { ok, err ->
                        if (!ok && err == "collision") {
                            AppMessages.show(accountNameExistsMessage)
                        }
                    }
                    onDismiss()
                }
            }) { Text(stringResource(UiR.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiR.string.cancel))
            }
        }
    )
}
