package com.vayunmathur.communicate.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.communicate.wear.data.WearThreadCache
import com.vayunmathur.communicate.wear.domain.WearCallEntry
import com.vayunmathur.communicate.wear.domain.WearCommunicateSnapshot
import com.vayunmathur.communicate.wear.domain.WearIncomingCall
import com.vayunmathur.communicate.wear.domain.WearMessage
import com.vayunmathur.communicate.wear.domain.WearThread
import com.vayunmathur.communicate.wear.domain.WearVoicemail

/** Round watch, 454x454px class (227dp at 320dpi). */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

private val fixtureThread = WearThread(key = "sim:1", displayName = "Ana Sol", address = "+1 555 0101", snippet = "See you at 6", unreadCount = 1)
private val fixtureSnapshot = WearCommunicateSnapshot(
    threads = listOf(fixtureThread, WearThread(key = "sim:2", displayName = "Ben Carter", snippet = "Sent the file")),
    calls = listOf(WearCallEntry(key = "c1", displayName = "Ana Sol", phoneNumber = "+1 555 0101", type = "Incoming", durationSeconds = 125)),
    voicemails = listOf(WearVoicemail(key = "v1", phoneNumber = "+1 555 0101", durationSeconds = 32, transcript = "Call me back about tomorrow", isRead = false)),
    voicemailUnreadCount = 1,
)

/**
 * Store listing images for `:communicate-wear` — every top-level screen/route
 * (conversation list, thread, reply, call log, voicemail, phone-only) plus the
 * incoming-call overlay that replaces any route.
 * `./gradlew :communicate-wear:metadata` copies renders into `metadata_data/photos/communicate-wear/wear/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-conversations-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1ConversationsRound() {
        RoundPreview {
            WearConversationListContent(snapshot = fixtureSnapshot, pendingCount = 0, alert = null, onOpenThread = {}, onOpenAlert = {}, onOpenCalls = {}, onOpenVoicemail = {}, onOpenPhoneOnly = {}, onRequestSync = { true })
        }
    }

    @PreviewTest
    @Preview(name = "2-thread-round", device = ROUND, group = "wear")
    @Composable
    fun Preview2ThreadRound() {
        RoundPreview {
            WearThreadContent(
                thread = fixtureThread,
                cache = WearThreadCache(messages = listOf(
                    WearMessage(messageKey = "m1", body = "Are we still on for 6?"),
                    WearMessage(messageKey = "m2", body = "Yes — see you there", outgoing = true),
                )),
                onLoadOlder = {},
                onReply = {},
                onMarkRead = {},
                onBack = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-reply-round", device = ROUND, group = "wear")
    @Composable
    fun Preview3ReplyRound() {
        RoundPreview { WearReplyContent(initialDraft = "On my way", onSend = {}, onDone = {}) }
    }

    @PreviewTest
    @Preview(name = "4-call-log-round", device = ROUND, group = "wear")
    @Composable
    fun Preview4CallLogRound() {
        RoundPreview { WearCallLogContent(snapshot = fixtureSnapshot, onBack = {}) }
    }

    @PreviewTest
    @Preview(name = "5-voicemail-round", device = ROUND, group = "wear")
    @Composable
    fun Preview5VoicemailRound() {
        RoundPreview { WearVoicemailContent(snapshot = fixtureSnapshot, onMarkRead = {}, onBack = {}) }
    }

    @PreviewTest
    @Preview(name = "6-phone-only-round", device = ROUND, group = "wear")
    @Composable
    fun Preview6PhoneOnlyRound() {
        RoundPreview { WearPhoneOnlyScreen(onBack = {}) }
    }

    @PreviewTest
    @Preview(name = "7-incoming-call-round", device = ROUND, group = "wear")
    @Composable
    fun Preview7IncomingCallRound() {
        RoundPreview {
            WearIncomingCallContent(call = WearIncomingCall(callKey = "call1", displayName = "Ana Sol"), onDecline = {}, onDone = {})
        }
    }
}

/** Round-watch framing: clip Layoutlib's rectangular canvas to the watch shape. */
@Composable
private fun RoundPreview(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colors.background)) { content() }
    }
}
