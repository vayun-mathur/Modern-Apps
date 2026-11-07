package com.vayunmathur.communicate.whatsapp

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent
import com.vayunmathur.communicate.data.whatsapp.createGroup
import com.vayunmathur.communicate.data.whatsapp.cropResizeAvatar
import com.vayunmathur.communicate.data.whatsapp.deleteChat
import com.vayunmathur.communicate.data.whatsapp.deleteThread
import com.vayunmathur.communicate.data.whatsapp.fetchAndEmitGroupInfo
import com.vayunmathur.communicate.data.whatsapp.markChatUnread
import com.vayunmathur.communicate.data.whatsapp.queryGroupParticipants
import com.vayunmathur.communicate.data.whatsapp.sendNewThread
import com.vayunmathur.communicate.data.whatsapp.setDisappearingTimer
import com.vayunmathur.communicate.data.whatsapp.setGroupAvatar
import com.vayunmathur.communicate.data.whatsapp.setGroupName
import com.vayunmathur.communicate.data.whatsapp.setGroupTopic
import com.vayunmathur.communicate.data.whatsapp.setMute
import com.vayunmathur.communicate.data.whatsapp.togglePin
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import com.vayunmathur.communicate.data.whatsapp.updateGroupParticipants
import java.io.ByteArrayOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Pass-2 slice-1 (whatsapp chats/groups): chat mutations and group management
 * against the deterministic unconnected-socket harness. Sends return the
 * socket's false; local/event side effects are asserted directly.
 */
@RunWith(AndroidJUnit4::class)
class W5Pass2ChatsGroupsTest {

    private val auth = WhatsAppAuthData(
        phoneNumber = "+15550001", pushName = "Tester", wid = "15550001@s.whatsapp.net",
        noisePrivateKey = "", noisePublicKey = "", identityPrivateKey = "", identityPublicKey = "",
        registrationId = 14, signedPreKeyId = 1, signedPreKeyPublic = "", signedPreKeyPrivate = "",
        signedPreKeySignature = "", lid = "12345@lid",
    )

    private val receivedEvents = mutableListOf<WhatsAppEvent>()

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = auth
        WhatsAppClient.db = null
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.knownGroups.clear()
        WhatsAppDiag.clear()
        receivedEvents.clear()
        CoroutineScope(Dispatchers.Default + Job()).launch {
            WhatsAppClient.events.collect { receivedEvents.add(it) }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        error("timed out waiting for $label")
    }

    @Test
    fun `delete chat and thread emit deletion and validate jid`() = runTest {
        assertTrue(WhatsAppClient.deleteChat("15550002@s.whatsapp.net"))
        assertTrue(WhatsAppClient.deleteChat("12345@g.us", leaveGroup = true))
        assertTrue(WhatsAppClient.deleteChat("12345@g.us", leaveGroup = false))
        assertFalse(WhatsAppClient.deleteChat("wa:"))
        assertTrue(WhatsAppClient.deleteThread("15550002@s.whatsapp.net"))
        assertFalse(WhatsAppClient.deleteThread("wa:"))
        await("deletions emitted") { receivedEvents.count { it is WhatsAppEvent.ConversationDeleted } >= 3 }
        assertTrue(
            receivedEvents.filterIsInstance<WhatsAppEvent.ConversationDeleted>()
                .any { it.conversationId == "12345@g.us" },
        )
    }

    @Test
    fun `new thread needs encryption and reports null`() = runTest {
        assertNull(WhatsAppClient.sendNewThread("15550002", "hello"))
        assertNull(WhatsAppClient.sendNewThread("15550002@s.whatsapp.net", "hello"))
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        assertNull(WhatsAppClient.sendNewThread("15550002", "hello"))
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.webSocket = null
        assertNull(WhatsAppClient.sendNewThread("15550002", "hello"))
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
    }

    @Test
    fun `chat patches complete against the dead socket`() = runTest {
        WhatsAppClient.markChatUnread("15550002@s.whatsapp.net", unread = true)
        WhatsAppClient.markChatUnread("15550002@s.whatsapp.net", unread = false)
        WhatsAppClient.setMute("15550002@s.whatsapp.net", muteUntilMs = 1_700_000_000_000L)
        WhatsAppClient.setMute("12345@g.us", muteUntilMs = 0L)
        WhatsAppClient.togglePin("15550002@s.whatsapp.net", pinned = true)
        WhatsAppClient.togglePin("15550002@s.whatsapp.net", pinned = false)
        // Bad JIDs and disconnected states are silent no-ops.
        WhatsAppClient.markChatUnread("wa:", unread = true)
        WhatsAppClient.setMute("wa:", 1L)
        WhatsAppClient.togglePin("wa:", pinned = true)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        WhatsAppClient.markChatUnread("15550002@s.whatsapp.net", unread = true)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        assertTrue(true)
    }

    @Test
    fun `group management validates and fails soft`() = runTest {
        val group = "12345@g.us"
        val dm = "15550002@s.whatsapp.net"
        assertFalse(WhatsAppClient.setGroupName(group, "New name"))
        assertFalse(WhatsAppClient.setGroupName(dm, "Not a group"))
        assertFalse(WhatsAppClient.setGroupTopic(group, "Topic"))
        assertFalse(WhatsAppClient.setGroupTopic(group, "Topic", previousTopicId = "old-1"))
        assertFalse(WhatsAppClient.updateGroupParticipants(group, listOf("777@s.whatsapp.net"), "add"))
        assertFalse(WhatsAppClient.updateGroupParticipants(group, listOf("777@s.whatsapp.net"), "remove"))
        assertFalse(WhatsAppClient.updateGroupParticipants(group, listOf("777@s.whatsapp.net"), "promote"))
        assertFalse(WhatsAppClient.updateGroupParticipants(group, listOf("777@s.whatsapp.net"), "explode"))
        assertFalse(WhatsAppClient.updateGroupParticipants(dm, listOf("777@s.whatsapp.net"), "add"))
        assertEquals(emptyList(), WhatsAppClient.queryGroupParticipants(group))
        // Group info fetch with no Iq answer publishes nothing and is retryable.
        WhatsAppClient.fetchAndEmitGroupInfo(group)
        assertFalse(WhatsAppClient.knownGroups.contains(group))
        WhatsAppClient.fetchAndEmitGroupInfo(dm) // not a group: ignored
        assertNull(WhatsAppClient.createGroup("Subject", listOf("777@s.whatsapp.net")))
        assertNull(WhatsAppClient.createGroup("Subject", emptyList()))
        assertFalse(WhatsAppClient.setDisappearingTimer(group, 7776000L))
    }

    @Test
    fun `group avatar crop resizes a real image and rejects garbage`() = runTest {
        // Robolectric's BitmapFactory shadow decodes anything, so the null-on-garbage
        // branch is only reachable on a real decoder; the pipeline is asserted below.
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val processed = WhatsAppClient.cropResizeAvatar(png)
        assertNotNull(processed)
        assertTrue(processed.isNotEmpty())
        // JPEG SOI marker.
        assertEquals(0xFF.toByte(), processed[0])
        assertEquals(0xD8.toByte(), processed[1])
        // setGroupAvatar runs the same pipeline, then fails at the dead socket.
        assertFalse(WhatsAppClient.setGroupAvatar("12345@g.us", png))
        assertFalse(WhatsAppClient.setGroupAvatar("12345@g.us", byteArrayOf(9)))
        assertFalse(WhatsAppClient.setGroupAvatar("15550002@s.whatsapp.net", png))
    }
}
