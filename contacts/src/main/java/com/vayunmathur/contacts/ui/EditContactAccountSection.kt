package com.vayunmathur.contacts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.NewContactRestriction
import com.vayunmathur.contacts.util.ContactAccount
import com.vayunmathur.contacts.util.ContactViewModel
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text

/**
 * Account chooser for new contacts (SIM accounts appear here as normal accounts).
 *
 * Depends on the ViewModel only for the draft type; the caller owns state updates.
 */
@Composable
fun EditContactAccountSection(
    draft: ContactViewModel.ContactDraft,
    accounts: List<ContactAccount>,
    simLabels: Map<String, String> = emptyMap(),
    restriction: NewContactRestriction? = null,
    onAccountChange: (String, String) -> Unit,
) {
    AccountChooser(draft.accountName, draft.accountType, accounts, simLabels, restriction) { name, type ->
        onAccountChange(name, type)
    }
}

/**
 * Accounts [restriction] blocks (Android 16+ with a cloud default account, #916) are listed
 * disabled with the reason, since the provider would refuse a new contact there.
 */
@Composable
internal fun AccountChooser(
    accountName: String,
    accountType: String,
    accounts: List<ContactAccount>,
    simLabels: Map<String, String> = emptyMap(),
    restriction: NewContactRestriction? = null,
    onAccountChange: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val onDevice = stringResource(R.string.on_device)
    val currentKey = "${accountType}|${accountName}"
    val displayValue = when {
        accountName.isEmpty() && accountType.isEmpty() -> onDevice
        simLabels.containsKey(currentKey) -> simLabels[currentKey]!!
        accountName.isNotEmpty() -> accountName
        else -> onDevice
    }
    val blockedReason = restriction?.let {
        stringResource(R.string.account_unavailable_cloud_default, it.cloudAccount.name)
    }
    val currentBlocked = restriction?.blocks(accountName, accountType) == true
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.account)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = currentBlocked,
            supportingText = if (currentBlocked && blockedReason != null) {
                { Text(blockedReason) }
            } else null,
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    IconArrowDropDown()
                }
            }
        )
        DropdownMenu(expanded, { expanded = false }) {
            if (accounts.none { it.name.isBlank() && it.type.isBlank() }) {
                val blocked = restriction?.blocks("", "") == true
                SelectableDropdownMenuItem(
                    selected = accountName.isEmpty() && accountType.isEmpty(),
                    onClick = {
                        onAccountChange("", "")
                        expanded = false
                    },
                    text = { Text(onDevice) },
                    enabled = !blocked,
                    supportingText = if (blocked && blockedReason != null) {
                        { Text(blockedReason) }
                    } else null,
                    selectedLeadingIcon = { com.vayunmathur.library.ui.IconCheck() },
                )
            }
            accounts.forEach { account ->
                val key = "${account.type}|${account.name}"
                val label = simLabels[key] ?: account.name.ifEmpty { onDevice }
                val blocked = restriction?.blocks(account.name, account.type) == true
                SelectableDropdownMenuItem(
                    selected = currentKey == key,
                    onClick = {
                        onAccountChange(account.name, account.type)
                        expanded = false
                    },
                    enabled = !blocked,
                    supportingText = if (blocked && blockedReason != null) {
                        { Text(blockedReason) }
                    } else null,
                    text = { Text(when {
                        simLabels.containsKey(key) -> label
                        account.type.isBlank() -> label
                        else -> stringResource(R.string.account_display_format, label, account.type)
                    }) },
                    selectedLeadingIcon = { com.vayunmathur.library.ui.IconCheck() },
                )
            }
        }
        Box(Modifier.matchParentSize().clickable { expanded = true })
    }
}
