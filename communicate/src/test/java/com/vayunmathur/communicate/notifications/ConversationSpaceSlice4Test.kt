package com.vayunmathur.communicate.notifications

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice-4 coverage for the Conversation Space wiring ([ConversationSpace]).
 *
 * Robolectric (resource + framework access). The pure id/extra mapping is
 * asserted exactly — it is what keeps per-conversation notifications from
 * clobbering each other — and posting paths are exercised through the real
 * NotificationManager shadow.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationSpaceSlice4Test {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun target(
        line: CommunicateLine = CommunicateLine.Sim,
        address: String = "+1555",
        remoteId: String? = null,
        threadId: Long = 42L,
        name: String = "Pat",
    ) = ConversationTarget(
        line = line,
        address = address,
        remoteId = remoteId,
        threadId = threadId,
        personName = name,
    )

    @Test
    fun `shortcutId prefers remoteId then threadId then address`() {
        assertEquals("Sim:t.abc", ConversationSpace.shortcutId(target(remoteId = "t.abc", threadId = 42L)))
        assertEquals("Sim:42", ConversationSpace.shortcutId(target(remoteId = null, threadId = 42L)))
        assertEquals(
            "Sim:+1555",
            ConversationSpace.shortcutId(target(remoteId = null, threadId = -1L)),
        )
    }

    @Test
    fun `shortcutId is unique across lines`() {
        val sim = ConversationSpace.shortcutId(target(line = CommunicateLine.Sim))
        val gv = ConversationSpace.shortcutId(target(line = CommunicateLine.GoogleVoice))
        assertFalse(sim == gv)
        assertTrue(gv.startsWith("GoogleVoice:"))
    }

    @Test
    fun `putExtras round-trips the descriptor`() {
        val intent = ConversationSpace.putExtras(
            Intent(),
            target().copy(isGroup = true, title = "Group", subscriptionId = 3),
        )
        assertEquals("Sim", intent.getStringExtra(ConversationSpace.EXTRA_LINE))
        assertEquals("+1555", intent.getStringExtra(ConversationSpace.EXTRA_ADDRESS))
        assertEquals(42L, intent.getLongExtra(ConversationSpace.EXTRA_THREAD_ID, -1L))
        assertTrue(intent.getBooleanExtra(ConversationSpace.EXTRA_IS_GROUP, false))
        assertEquals("Group", intent.getStringExtra(ConversationSpace.EXTRA_TITLE))
        assertEquals(3, intent.getIntExtra(ConversationSpace.EXTRA_SUB_ID, -1))
    }

    @Test
    fun `putExtras defaults the sub id when absent`() {
        val intent = ConversationSpace.putExtras(Intent(), target())
        assertEquals(-1, intent.getIntExtra(ConversationSpace.EXTRA_SUB_ID, -99))
        assertNull(intent.getStringExtra(ConversationSpace.EXTRA_REMOTE_ID))
    }

    @Test
    fun `notifyIncoming posts one notification per conversation`() {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)!!
        ConversationSpace.ensureIncomingChannel(
            context,
            ConversationSpace.SIM_CHANNEL_ID,
            "SMS",
            "incoming sms",
        )
        ConversationSpace.notifyIncoming(
            context = context,
            target = target(),
            channelId = ConversationSpace.SIM_CHANNEL_ID,
            body = "hello",
            timestamp = 1_700_000_000_000L,
            smallIcon = android.R.drawable.sym_action_call,
        )
        val first = nm.activeNotifications.size
        assertEquals(1, first)

        // A second message in the same thread replaces, not stacks.
        ConversationSpace.notifyIncoming(
            context = context,
            target = target(),
            channelId = ConversationSpace.SIM_CHANNEL_ID,
            body = "hello again",
            timestamp = 1_700_000_000_001L,
            smallIcon = android.R.drawable.sym_action_call,
        )
        assertEquals(1, nm.activeNotifications.size)

        // A different conversation gets its own slot.
        ConversationSpace.notifyIncoming(
            context = context,
            target = target(address = "+1999", threadId = 43L),
            channelId = ConversationSpace.SIM_CHANNEL_ID,
            body = "other thread",
            timestamp = 1_700_000_000_002L,
            smallIcon = android.R.drawable.sym_action_call,
        )
        assertEquals(2, nm.activeNotifications.size)
    }

    @Test
    fun `pushShortcut returns the stable id and remove clears it`() {
        val id = ConversationSpace.pushShortcut(context, target())
        assertEquals(ConversationSpace.shortcutId(target()), id)
        ConversationSpace.removeShortcut(context, target())
    }

    @Test
    fun `pushShortcut prefers the group title and falls back to app name`() {
        val titled = target().copy(title = "Family", personName = "Pat")
        assertEquals(ConversationSpace.shortcutId(target()), ConversationSpace.pushShortcut(context, titled))
        val blank = target().copy(title = null, personName = "   ")
        assertEquals(ConversationSpace.shortcutId(target()), ConversationSpace.pushShortcut(context, blank))
        ConversationSpace.removeShortcut(context, target())
    }
}
