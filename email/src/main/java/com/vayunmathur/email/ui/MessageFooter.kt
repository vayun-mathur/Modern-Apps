package com.vayunmathur.email.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.email.R
import com.vayunmathur.email.data.Attachment
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.UnsubscribeMethod
import com.vayunmathur.email.data.detectUnsubscribe
import com.vayunmathur.email.platform.MessageThreadActions
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconForward
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.AppMessages

/**
 * Everything under a message body: its attachments, the Reply/Forward row,
 * and Unsubscribe (when the message offers one) and Block sender.
 */
@Composable
fun MessageFooter(
    msg: EmailMessage,
    attachments: List<Attachment>,
    actions: MessageThreadActions,
    onBack: () -> Unit,
    onReply: (String, String, String?) -> Unit,
    onForward: (String, String?) -> Unit,
    onCompose: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    if (attachments.isNotEmpty()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.attachments_2), style = MaterialTheme.typography.labelLarge)
            attachments.forEach { att -> AttachmentItem(att, actions) }
        }
    }
    Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onReply(msg.from, msg.subject, msg.serverId) }, modifier = Modifier.weight(1f)) {
            IconUndo(modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.reply))
        }
        OutlinedButton(onClick = { onForward(msg.subject, msg.body) }, modifier = Modifier.weight(1f)) {
            IconForward(modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.forward))
        }
    }
    Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val unsubscribe = remember(msg.listUnsubscribe, msg.listUnsubscribePost, msg.body, msg.isHtml) { msg.detectUnsubscribe() }
        if (unsubscribe != null) {
            var showConfirm by remember(msg.id) { mutableStateOf(false) }
            TextButton(onClick = { showConfirm = true }) { Text(stringResource(R.string.unsubscribe)) }
            if (showConfirm) {
                UnsubscribeDialog(method = unsubscribe, onDismiss = { showConfirm = false }, onConfirm = { showConfirm = false; performUnsubscribe(unsubscribe, context, actions, onCompose) })
            }
        }
        TextButton(onClick = { actions.blockSender(msg.from); AppMessages.show(resources.getString(R.string.sender_blocked)); onBack() }) { Text(stringResource(R.string.block_sender)) }
    }
}

@Composable
private fun UnsubscribeDialog(method: UnsubscribeMethod, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val (message, confirmLabel) = when (method) {
        is UnsubscribeMethod.OneClickPost -> "Send an unsubscribe request to the sender?" to "Unsubscribe"
        is UnsubscribeMethod.OpenWeb -> "Open the unsubscribe page in your browser?" to "Open"
        is UnsubscribeMethod.SendMail -> "Compose an unsubscribe email to ${method.address}?" to "Compose"
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.unsubscribe)) }, text = { Text(message) }, confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(com.vayunmathur.library.ui.R.string.cancel)) } })
}

private fun performUnsubscribe(method: UnsubscribeMethod, context: Context, actions: MessageThreadActions, onCompose: (String, String) -> Unit) {
    when (method) {
        is UnsubscribeMethod.OneClickPost -> {
            AppMessages.show(context.getString(R.string.unsubscribing))
            actions.oneClickUnsubscribe(method.url) { ok -> AppMessages.show(if (ok) "Unsubscribed" else "Unsubscribe failed") }
        }
        is UnsubscribeMethod.OpenWeb -> {
            val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, method.url.toUri())) }.isSuccess
            if (!opened) AppMessages.show(context.getString(R.string.couldn_t_open_unsubscribe_page))
        }
        is UnsubscribeMethod.SendMail -> onCompose(method.address, "Unsubscribe")
    }
}
