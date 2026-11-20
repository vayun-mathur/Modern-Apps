package com.vayunmathur.communicate.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
import com.vayunmathur.communicate.data.markRcsRead
import com.vayunmathur.communicate.data.markSignalRead
import com.vayunmathur.communicate.data.markSimThreadRead
import com.vayunmathur.communicate.data.markWhatsAppRead
import com.vayunmathur.communicate.data.updateGoogleVoiceThread
import com.vayunmathur.communicate.notifications.ConversationSpace

/**
 * Per-line side effects for an open conversation: mark-read dispatch plus the
 * foreground polling loops for lines with no realtime channel yet.
 *
 * Extracted from [ConversationScreen] to keep that file under the 350-line
 * `ui` package limit. Read receipts mirror across all four lines; polling
 * ticks bump [onRefreshTick] so the message list re-fetches.
 */
@Composable
fun ConversationReadEffects(
    context: Context,
    line: CommunicateLine,
    threadId: Long,
    remoteId: String?,
    address: String,
    onRefreshTick: () -> Unit,
) {
    // Cancel-on-read: opening the conversation clears its notification from the shade,
    // matching the tag/id ConversationSpace.notifyIncoming posted it under.
    LaunchedEffect(line, remoteId, threadId, address) {
        ConversationSpace.cancelIncoming(context, line, address, remoteId, threadId)
    }
    // Opening a SIM thread clears the provider's unread flags. Nothing else writes them back, and
    // the badge is recomputed from the provider, so imported rows would stay unread forever (#562).
    LaunchedEffect(threadId, line) {
        if (line == CommunicateLine.Sim) {
            CommunicateRepository.markSimThreadRead(context, threadId)
        }
    }
    // Opening a Google Voice thread marks it read server-side via batchupdateattributes.
    LaunchedEffect(remoteId, line) {
        if (line == CommunicateLine.GoogleVoice && remoteId != null) {
            CommunicateRepository.updateGoogleVoiceThread(
                context, remoteId, GoogleVoiceParser.ThreadAction.MarkRead,
            )
        }
    }
    // Foreground polling for the open GV thread (no realtime channel yet).
    LaunchedEffect(remoteId, line) {
        if (line == CommunicateLine.GoogleVoice && remoteId != null) {
            while (true) {
                kotlinx.coroutines.delay(10_000)
                onRefreshTick()
            }
        }
    }
    // WhatsApp messages land in local Room via the socket→event-processor; poll the cache so inbound
    // (and our own outgoing echo) appear live while the conversation is open. Cheap local reads.
    LaunchedEffect(line) {
        if (line == CommunicateLine.WhatsApp) {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                onRefreshTick()
            }
        }
    }
    // Send WhatsApp read receipts (and clear the unread badge) for the open conversation. Re-runs on
    // each poll tick; the repository guards against re-sending for an already-read message.
    var waLastReadId by remember(remoteId, address) { mutableStateOf<String?>(null) }
    LaunchedEffect(line, remoteId, address) {
        if (line == CommunicateLine.WhatsApp) {
            waLastReadId = CommunicateRepository.markWhatsAppRead(context, remoteId, address, waLastReadId)
        }
    }
    // Signal messages land in local Room via the socket→event-processor; poll so inbound
    // (and our own outgoing echo) appear live while the conversation is open.
    LaunchedEffect(line) {
        if (line == CommunicateLine.Signal) {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                onRefreshTick()
            }
        }
    }
    // Send Signal read receipts for the open conversation (mirrors WhatsApp).
    var sigLastReadId by remember(remoteId, address) { mutableStateOf<String?>(null) }
    LaunchedEffect(line, remoteId, address) {
        if (line == CommunicateLine.Signal) {
            sigLastReadId = try {
                CommunicateRepository.markSignalRead(context, remoteId, address, sigLastReadId)
            } catch (_: Throwable) {
                sigLastReadId
            }
        }
    }
    // RCS messages land in local Room via the SIP delegate; poll so inbound
    // (and our own outgoing echo) appear live while the conversation is open.
    LaunchedEffect(line) {
        if (line == CommunicateLine.Rcs) {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                onRefreshTick()
            }
        }
    }
    // Clear the RCS unread badge for the open conversation (no receipt protocol in v1).
    LaunchedEffect(line, remoteId, address) {
        if (line == CommunicateLine.Rcs) {
            runCatching {
                CommunicateRepository.markRcsRead(context, remoteId, address)
            }
        }
    }
}
