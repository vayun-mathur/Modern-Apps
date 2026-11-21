package com.vayunmathur.email.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.R
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * Sender picker row plus the To/Cc/Bcc recipient fields, extracted from
 * [ComposerScreen] to keep that file under the length limit.
 * Behavior identical — only moved.
 */
@Composable
fun ComposerHeaderSection(
    accounts: List<EmailAccount>,
    fromAccount: EmailAccount?,
    showAccountPicker: Boolean,
    onOpenAccountPicker: () -> Unit,
    onDismissAccountPicker: () -> Unit,
    onSelectAccount: (EmailAccount) -> Unit,
    to: String,
    onToChange: (String) -> Unit,
    cc: String,
    onCcChange: (String) -> Unit,
    bcc: String,
    onBccChange: (String) -> Unit,
    showCcBcc: Boolean,
    onToggleCcBcc: () -> Unit,
    onPickContact: (Int) -> Unit,
) {
    Surface(
        onClick = onOpenAccountPicker,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${stringResource(R.string.from_label)}: ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                fromAccount?.email ?: stringResource(R.string.select_account),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconChevronRight()
        }
    }

    if (showAccountPicker) {
        AlertDialog(
            onDismissRequest = onDismissAccountPicker,
            confirmButton = {},
            title = { Text(stringResource(R.string.select_sender)) },
            text = {
                Column {
                    accounts.forEach { acc ->
                        ListItem(
                            content = { Text(acc.email) },
                            modifier = Modifier.clickable {
                                onSelectAccount(acc)
                            }
                        )
                    }
                }
            }
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = to, onValueChange = onToChange,
            label = { Text(stringResource(R.string.to_label)) },
            trailingIcon = { IconButton(onClick = { onPickContact(0) }) { com.vayunmathur.library.ui.IconAdd() } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggleCcBcc) { Text(stringResource(R.string.cc_bcc)) }
    }
    if (showCcBcc) {
        OutlinedTextField(
            value = cc, onValueChange = onCcChange, label = { Text(stringResource(R.string.cc)) },
            trailingIcon = { IconButton(onClick = { onPickContact(1) }) { com.vayunmathur.library.ui.IconAdd() } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = bcc, onValueChange = onBccChange, label = { Text(stringResource(R.string.bcc)) },
            trailingIcon = { IconButton(onClick = { onPickContact(2) }) { com.vayunmathur.library.ui.IconAdd() } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
