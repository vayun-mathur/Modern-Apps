package com.vayunmathur.contacts.ui.dialogs

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.contacts.util.VcfUtils
import okio.source

@Composable
fun ImportVcfDialog(
    viewModel: ContactViewModel,
    uris: List<Uri>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val accounts by viewModel.accounts.collectAsState()
    var selectedAccount by remember { mutableStateOf<com.vayunmathur.contacts.util.ContactAccount?>(null) }
    var showCreateAccount by remember { mutableStateOf(false) }
    var newAccountName by remember { mutableStateOf("") }

    if (showCreateAccount) {
        AlertDialog(
            onDismissRequest = { showCreateAccount = false },
            title = { Text(stringResource(R.string.add_contacts_account)) },
            text = {
                TextField(
                    value = newAccountName,
                    onValueChange = { newAccountName = it },
                    label = { Text(stringResource(R.string.account_name)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newAccountName.isNotBlank()) {
                        viewModel.createAccount(newAccountName)
                        showCreateAccount = false
                        newAccountName = ""
                    }
                }) { Text(stringResource(R.string.add)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateAccount = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_contacts)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("Select account to import to:")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(accounts, key = { "${it.type}|${it.name}" }) { account ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccount = account }
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(account.name)
                            RadioButton(selected = selectedAccount == account, onClick = { selectedAccount = account })
                        }
                    }
                    item {
                        TextButton(onClick = { showCreateAccount = true }) {
                            Text("+ Create new account")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedAccount != null,
                onClick = {
                    val account = selectedAccount ?: return@TextButton
                    uris.forEach { uri ->
                        val contacts = context.contentResolver.openInputStream(uri)?.source()?.use { stream ->
                            VcfUtils.parseContacts(stream)
                        } ?: emptyList()
                        contacts.forEach { contact ->
                            val toSave = contact.copy(
                                accountName = account.name,
                                accountType = account.type
                            )
                            toSave.save(context, toSave.details, ContactDetails.empty())
                        }
                    }
                    viewModel.loadContacts()
                    onDismiss()
                }
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
