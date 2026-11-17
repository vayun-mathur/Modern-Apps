package com.vayunmathur.findfamily.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import androidx.compose.ui.res.stringResource
import com.vayunmathur.findfamily.R

/**
 * Shows the connection's verification **security code**. Both people open this for each other and
 * compare the numbers; if they match, the end-to-end-encrypted link is verified and no one (not
 * even the server) has substituted a key to intercept it.
 */
@Composable
fun SecurityCodeDialog(user: User, ffViewModel: FindFamilyViewModel, onDismiss: () -> Unit) {
    var code by remember(user.id) { mutableStateOf<String?>(null) }
    var loading by remember(user.id) { mutableStateOf(true) }
    LaunchedEffect(user.id) {
        code = ffViewModel.securityCodeFor(user)
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.security_code)) },
        text = {
            Column {
                Text(stringResource(R.string.compare_this_code_with_on_their_device_i, user.name))
                Spacer(Modifier.height(16.dp))
                when {
                    loading -> Text(stringResource(R.string.computing))
                    code == null -> Text(stringResource(R.string.couldn_t_compute_yet_s_key_isn_t_availab, user.name))
                    else -> Text(
                        code!!,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.done)) } }
    )
}
