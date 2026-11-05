package com.vayunmathur.communicate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.communicate.data.CommunicateCallLogEntry
import com.vayunmathur.communicate.data.CommunicateCallType
import com.vayunmathur.communicate.data.CommunicateContact
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.IconVoicemail
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:communicate` phone tabs, rendered from Compose previews.
 *
 * The car shots live in CarMetadataPreviews (head-unit UI, no phone chrome). These
 * cover the handset tabs: Messages, Dialer, Call logs, Voicemail. The tab screens were split
 * into stateless `*Content` slices so previews render without the SMS provider,
 * the SIM/session lines, or the contact/call-log content providers.
 */
class MetadataPreviews {

    private val threads = listOf(
        SmsThread(
            threadId = 1,
            address = "+14155550102",
            displayName = "Aisha Rahman",
            snippet = "Running 10 minutes late, start without me",
            timestampMillis = 1_785_566_534_600,
            unreadCount = 2,
        ),
        SmsThread(
            threadId = 2,
            address = "+14155550103",
            displayName = "Weekend Hike",
            snippet = "Sam: Trailhead at 8?",
            timestampMillis = 1_785_563_000_000,
            unreadCount = 0,
            isGroup = true,
            participants = listOf("+14155550103", "+14155550104"),
            groupTitle = "Weekend Hike",
        ),
        SmsThread(
            threadId = 3,
            address = "+14155550105",
            displayName = null,
            snippet = "Your package arrives Thursday",
            timestampMillis = 1_785_550_000_000,
            unreadCount = 0,
        ),
    )

    private val contacts = listOf(
        CommunicateContact(id = 1, name = "Aisha Rahman", phoneNumber = "+14155550102", label = "Mobile"),
        CommunicateContact(id = 2, name = "Sam Whitfield", phoneNumber = "+14155550103", label = "Home"),
        CommunicateContact(id = 3, name = "Maya Chen", phoneNumber = "+14155550105", label = "Work"),
    )

    private val callLogs = listOf(
        CommunicateCallLogEntry(
            id = 1,
            displayName = "Aisha Rahman",
            phoneNumber = "+14155550102",
            type = CommunicateCallType.Incoming,
            timestampMillis = 1_785_566_000_000,
            durationSeconds = 184,
        ),
        CommunicateCallLogEntry(
            id = 2,
            displayName = null,
            phoneNumber = "+14155550109",
            type = CommunicateCallType.Missed,
            timestampMillis = 1_785_560_000_000,
            durationSeconds = 0,
        ),
        CommunicateCallLogEntry(
            id = 3,
            displayName = "Sam Whitfield",
            phoneNumber = "+14155550103",
            type = CommunicateCallType.Outgoing,
            timestampMillis = 1_785_550_000_000,
            durationSeconds = 62,
        ),
    )

    private val voicemails = listOf(
        VoicemailEntry(
            id = 1,
            phoneNumber = "+14155550102",
            timestampMillis = 1_785_566_000_000,
            durationSeconds = 42,
            contentUri = "content://com.android.voicemail/voicemail/1",
            mimeType = "audio/amr",
            hasAudio = true,
            transcript = null,
            isRead = false,
        ),
        VoicemailEntry(
            id = 2,
            phoneNumber = "+14155550109",
            timestampMillis = 1_785_560_000_000,
            durationSeconds = 18,
            contentUri = "content://com.android.voicemail/voicemail/2",
            mimeType = "audio/amr",
            hasAudio = true,
            transcript = "Hey, it\'s Maya — call me back when you get this, it\'s about Thursday.",
            isRead = true,
        ),
        VoicemailEntry(
            id = 3,
            phoneNumber = "Unknown",
            timestampMillis = 1_785_550_000_000,
            durationSeconds = 0,
            contentUri = "content://com.android.voicemail/voicemail/3",
            mimeType = null,
            hasAudio = false,
            transcript = null,
            isRead = true,
        ),
    )

    @PreviewTest
    @Preview(name = "1-messages", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Messages() {
        ChromePreview(selectedTab = 0)
    }

    @PreviewTest
    @Preview(name = "2-dialer", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Dialer() {
        ChromePreview(selectedTab = 1)
    }

    @PreviewTest
    @Preview(name = "3-call-logs", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3CallLogs() {
        ChromePreview(selectedTab = 2)
    }

    @PreviewTest
    @Preview(name = "4-voicemail", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Voicemail() {
        ChromePreview(selectedTab = 3)
    }

    @Composable
    private fun MessagesContent() {
        MessagesListContent(
            threads = threads,
            onOpenThread = {},
            onDeleteThread = {},
        )
    }

    @Composable
    private fun DialerContentTab() {
        DialerContent(
            contacts = contacts,
            onPlace = {},
        )
    }

    @Composable
    private fun CallLogsContentTab() {
        CallLogsContent(
            entries = callLogs,
            onCall = {},
            onDelete = {},
        )
    }

    @Composable
    private fun VoicemailContentTab() {
        VoicemailContent(
            entries = voicemails,
            expandedId = 2L,
            onToggleExpand = {},
            onDelete = {},
        )
    }

    /**
     * The app's real tab chrome around preview screen content: the same
     * [TabbedPagerScaffold] with the same Messages/Dialer/Call-logs/Voicemail
     * tabs `CommunicateTabs` declares. Only the selected tab is literal.
     */
    @Composable
    private fun ChromePreview(selectedTab: Int) {
        DynamicTheme(darkTheme = true) {
            val tabs = listOf(
                PagerTab("Messages", { IconSms() }) { MessagesContent() },
                PagerTab("Dialer", { IconCall() }) { DialerContentTab() },
                PagerTab("Call logs", { IconHistory() }) { CallLogsContentTab() },
                PagerTab("Voicemail", { IconVoicemail() }) { VoicemailContentTab() },
            )
            TabbedPagerScaffold(
                tabs = tabs,
                pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { tabs.size }),
                tabStyle = TabStyle.BottomNav,
            )
        }
    }
}
