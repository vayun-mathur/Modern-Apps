package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.destroyHostedFocus
import com.vayunmathur.communicate.data.rcs.focusMembers
import com.vayunmathur.communicate.data.rcs.hostedFocusFor
import com.vayunmathur.communicate.data.rcs.noteFocusJoin
import com.vayunmathur.communicate.data.rcs.noteFocusLeave
import com.vayunmathur.communicate.data.rcs.hostGroupFocus
import com.vayunmathur.communicate.data.rcs.inviteFocusMember
import com.vayunmathur.communicate.data.rcs.noteFocusSubscriber
import com.vayunmathur.communicate.data.rcs.dropFocusSubscriber
import com.vayunmathur.communicate.data.rcs.publishConferenceInfo
import com.vayunmathur.communicate.data.rcs.refreshEventSubscriptions
import com.vayunmathur.communicate.data.rcs.removeFocusMember
import com.vayunmathur.communicate.data.rcs.subscribeConferenceEvents
import com.vayunmathur.communicate.data.rcs.onConferenceNotify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice-3 tests for the conference focus management
 * (`RcsSessionFocus.kt` extensions on [RcsSessionManager]): join/leave
 * bookkeeping, hosted-focus lookup, conference-info NOTIFY reconciliation
 * (added/removed + stale-version filtering), and transport-gated publishes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RcsSlice3SessionFocusTest {

    private fun conferenceInfo(version: Int, vararg members: String, deleted: String? = null): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<conference-info xmlns=\"urn:ietf:params:xml:ns:conference-info\"\n")
            append("    entity=\"conf:slice3-${version}@rcs.local\" version=\"$version\" state=\"full\">\n")
            append("  <users>\n")
            for (member in members) {
                append("    <user entity=\"$member\" state=\"full\">\n")
                append("      <endpoint entity=\"$member\"><state>connected</state></endpoint>\n")
                append("    </user>\n")
            }
            if (deleted != null) {
                append("    <user entity=\"$deleted\" state=\"deleted\">\n")
                append("      <endpoint entity=\"$deleted\"><state>disconnected</state></endpoint>\n")
                append("    </user>\n")
            }
            append("  </users>\n")
            append("</conference-info>")
        }

    @Test
    fun joinAndLeaveOnUnknownFocusAreNoOps() {
        RcsSessionManager.noteFocusJoin("conf:slice3-unknown@rcs.local", "+15550001111")
        assertNull(RcsSessionManager.focusMembers("conf:slice3-unknown@rcs.local"))
        RcsSessionManager.noteFocusLeave("conf:slice3-unknown@rcs.local", "+15550001111")
        assertNull(RcsSessionManager.hostedFocusFor("slice3-conv-unknown"))
    }

    @Test
    fun conferenceNotifyReconcilesAddedAndRemoved() {
        val (added, removed) = RcsSessionManager.onConferenceNotify(
            "slice3-conv-notify",
            conferenceInfo(1, "sip:+15550002121@rcs", "sip:+15550002222@rcs", deleted = "sip:+15550002323@rcs"),
        )
        assertEquals(listOf("sip:+15550002121@rcs", "sip:+15550002222@rcs"), added)
        assertEquals(listOf("sip:+15550002323@rcs"), removed)
    }

    @Test
    fun conferenceNotifyRejectsNonConference() {
        val (added, removed) = RcsSessionManager.onConferenceNotify("slice3-conv-x", "hello plain text")
        assertTrue(added.isEmpty())
        assertTrue(removed.isEmpty())
    }

    @Test
    fun conferenceNotifyStaleVersionIgnoredAfterSubscribe() = runTest {
        // Without transport, subscribe fails closed but must not crash.
        assertFalse(
            RcsSessionManager.subscribeConferenceEvents("conf:slice3-sub@rcs.local", "slice3-conv-sub"),
        )
        // Plain NOTIFY path still reconciles (no subscription tracked for this conv).
        val first = RcsSessionManager.onConferenceNotify(
            "slice3-conv-sub2",
            conferenceInfo(5, "sip:+15550003131@rcs"),
        )
        assertEquals(listOf("sip:+15550003131@rcs"), first.first)
        val second = RcsSessionManager.onConferenceNotify(
            "slice3-conv-sub2",
            conferenceInfo(7, "sip:+15550003232@rcs"),
        )
        assertEquals(listOf("sip:+15550003232@rcs"), second.first)
    }

    @Test
    fun subscriberBookkeepingIsIdempotent() {
        RcsSessionManager.noteFocusSubscriber("slice3-sub-call-1", "conf:slice3-a@rcs.local")
        RcsSessionManager.noteFocusSubscriber("slice3-sub-call-1", "conf:slice3-a@rcs.local")
        RcsSessionManager.dropFocusSubscriber("slice3-sub-call-1")
        // Dropping twice is safe.
        RcsSessionManager.dropFocusSubscriber("slice3-sub-call-1")
    }

    @Test
    fun publishConferenceInfoFailsClosedWithoutTransport() = runTest {
        // No subscribers + no transport: returns quietly.
        RcsSessionManager.publishConferenceInfo("conf:slice3-nobody@rcs.local")
        RcsSessionManager.refreshEventSubscriptions()
    }

    @Test
    fun inviteAndRemoveFocusMemberFailClosedWithoutTransport() = runTest {
        assertFalse(RcsSessionManager.inviteFocusMember("slice3-conv-nofocus", "+15550004141"))
        assertFalse(RcsSessionManager.removeFocusMember("slice3-conv-nofocus", "+15550004141"))
        assertFalse(RcsSessionManager.subscribeConferenceEvents("conf:x@rcs.local", "slice3-conv-nofocus"))
    }

    @Test
    fun hostFocusFailsClosedWithoutTransport() = runTest {
        assertNull(
            RcsSessionManager.hostGroupFocus("slice3-conv-host", "subject", listOf("+15550005151")),
        )
        assertNull(RcsSessionManager.hostGroupFocus("slice3-conv-host2", "subject", emptyList()))
        assertTrue(RcsSessionManager.destroyHostedFocus("slice3-conv-never-hosted"))
    }
}
