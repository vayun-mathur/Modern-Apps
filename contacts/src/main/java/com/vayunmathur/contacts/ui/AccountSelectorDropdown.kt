package com.vayunmathur.contacts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * Destination picker for the vCard import screen. Accounts [restriction] blocks
 * (Android 16+ with a cloud default account, #916) are listed disabled with the reason.
 */
@Composable
fun AccountSelectorDropdown(
    accounts: List<ContactAccount>,
    selectedAccount: ContactAccount?,
    modifier: Modifier = Modifier,
    restriction: NewContactRestriction? = null,
    onSelect: (ContactAccount) -> Unit,
) {
    var showDropdown by remember { mutableStateOf(false) }
    val onDevice = stringResource(R.string.on_device)
    val blockedReason = restriction?.let {
        stringResource(R.string.account_unavailable_cloud_default, it.cloudAccount.name)
    }

    Box(modifier) {
        ListItem(
            content = { Text(selectedAccount?.name?.ifEmpty { onDevice } ?: stringResource(R.string.select_account)) },
            trailingContent = { IconArrowDropDown() },
            modifier = Modifier.clickable { showDropdown = true },
        )
        DropdownMenu(expanded = showDropdown, onDismissRequest = { showDropdown = false }) {
            accounts.forEach { account ->
                val blocked = restriction?.blocks(account.name, account.type) == true
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(account.name.ifEmpty { onDevice })
                            if (blocked && blockedReason != null) {
                                Text(blockedReason, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    onClick = {
                        onSelect(account)
                        showDropdown = false
                    },
                    enabled = !blocked,
                )
            }
        }
    }
}
