package com.vayunmathur.communicate.ui

import android.Manifest
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.acceptSignalIdentity
import com.vayunmathur.communicate.data.loadSmsMessagesMerged
import com.vayunmathur.communicate.data.signalPendingIdentityChange
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Conversation pane content: poll composer, Signal safety-number and RCS
 * encryption banners, and the message list behind its permission gates.
 * Extracted from [ConversationScreen] to keep that file under the length
 * limit. Behavior identical — only moved.
 */
@Composable
fun ConversationBody(
    padding: PaddingValues,
    line: CommunicateLine,
    remoteId: String?,
    address: String,
    refresh: Int,
    onRefresh: () -> Unit,
    thread: SmsThread,
    showPollComposer: Boolean,
    onDismissPollComposer: () -> Unit,
    onCreatePoll: (String, List<String>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val safetyNumberFailedMessage = stringResource(R.string.signal_safety_number_accept_failed)
    if (showPollComposer) {
        PollComposerDialog(
            onDismiss = onDismissPollComposer,
            onCreate = onCreatePoll,
        )
    }
    // A changed Signal identity key blocks sending until the user verifies it, so the warning has to
    // be in front of them here rather than only in a notification.
    var safetyNumber by remember(remoteId, address) { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(line, remoteId, address, refresh) {
        safetyNumber = if (line == CommunicateLine.Signal) {
            try {
                CommunicateRepository.signalPendingIdentityChange(context, remoteId, address)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }
    safetyNumber?.let { (number, keyHex) ->
        SafetyNumberBanner(
            safetyNumber = number,
            onAccept = {
                scope.launch {
                    val ok = CommunicateRepository.acceptSignalIdentity(context, remoteId, address, keyHex)
                    if (ok) {
                        safetyNumber = null
                        onRefresh()
                    } else {
                        AppMessages.show(safetyNumberFailedMessage)
                    }
                }
            },
            modifier = Modifier.padding(padding),
        )
    }
    RcsEncryptionBanner(
        line = line,
        remoteId = remoteId,
        address = address,
        refresh = refresh,
        modifier = Modifier.padding(padding),
    )
    // Google Voice threads don't require the default-SMS role or READ_SMS; only SIM does.
    if (line == CommunicateLine.GoogleVoice) {
        MessagesList(padding, refresh) {
            CommunicateRepository.loadSmsMessagesMerged(context, thread)
        }
        return
    }
    DefaultSmsGate(modifier = Modifier.padding(padding)) { roleRevision ->
        PermissionGate(
            permission = Manifest.permission.READ_SMS,
            message = stringResource(R.string.permission_sms_message),
            modifier = Modifier.padding(padding),
        ) { permissionRevision ->
            val messages = androidx.compose.runtime.produceState<List<com.vayunmathur.communicate.data.SmsMessage>?>(
                initialValue = null,
                thread.threadId,
                roleRevision,
                permissionRevision,
                refresh) {
                value = withContext(Dispatchers.IO) { CommunicateRepository.loadSmsMessagesMerged(context, thread) }
            }
            MessagesContent(padding, messages.value)
        }
    }
}
