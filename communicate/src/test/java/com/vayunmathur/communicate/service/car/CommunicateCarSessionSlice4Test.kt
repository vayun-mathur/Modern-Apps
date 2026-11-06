package com.vayunmathur.communicate.service.car

import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SmsThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice-4 coverage for the car session routing ([CommunicateCarSession]).
 *
 * Robolectric (the [Session] base needs the framework). Asserts the
 * thread→line mapping and the Google Voice read-only rule — the decisions that
 * keep car replies on lines that can actually send.
 */
@RunWith(RobolectricTestRunner::class)
class CommunicateCarSessionSlice4Test {

    private fun thread(
        line: CommunicateLine,
        subscriptionId: Int? = null,
        displayName: String? = null,
    ) = SmsThread(
        threadId = 7L,
        address = "+1555",
        displayName = displayName,
        snippet = "hi",
        timestampMillis = 1L,
        unreadCount = 0,
        line = line,
        subscriptionId = subscriptionId,
    )

    @Test
    fun `lineChoice maps every line`() {
        val session = CommunicateCarSession()
        assertEquals(LineChoice.GoogleVoice, session.lineChoiceFor(thread(CommunicateLine.GoogleVoice)))
        assertEquals(LineChoice.WhatsApp, session.lineChoiceFor(thread(CommunicateLine.WhatsApp)))
        assertEquals(LineChoice.Signal, session.lineChoiceFor(thread(CommunicateLine.Signal)))
        assertEquals(LineChoice.Rcs, session.lineChoiceFor(thread(CommunicateLine.Rcs)))
        assertEquals(
            LineChoice.Sim(3, "SIM 1"),
            session.lineChoiceFor(thread(CommunicateLine.Sim, subscriptionId = 3, displayName = "SIM 1")),
        )
    }

    @Test
    fun `sim choice falls back to address when unnamed`() {
        val session = CommunicateCarSession()
        assertEquals(
            LineChoice.Sim(-1, "+1555"),
            session.lineChoiceFor(thread(CommunicateLine.Sim)),
        )
    }

    @Test
    fun `only google voice threads are read-only`() {
        val session = CommunicateCarSession()
        assertFalse(session.canReply(thread(CommunicateLine.GoogleVoice)))
        assertTrue(session.canReply(thread(CommunicateLine.Sim)))
        assertTrue(session.canReply(thread(CommunicateLine.WhatsApp)))
        assertTrue(session.canReply(thread(CommunicateLine.Signal)))
        assertTrue(session.canReply(thread(CommunicateLine.Rcs)))
    }
}
