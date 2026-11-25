package com.vayunmathur.communicate.data

import com.vayunmathur.communicate.domain.WearCommLine
import com.vayunmathur.communicate.domain.WearCommOp
import com.vayunmathur.communicate.domain.WearOpKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WearCommunicateOpTest {

    private class FakeHandler(
        var replyOk: Boolean = true,
        var readOk: Boolean = true,
    ) : WearOpHandler {
        val calls = mutableListOf<String>()

        override suspend fun quickReply(op: WearCommOp, body: String): Boolean {
            calls += "reply:${op.threadKey}:$body"
            return replyOk
        }

        override suspend fun markRead(op: WearCommOp): Boolean {
            calls += "read:${op.threadKey}"
            return readOk
        }

        override suspend fun markVoicemailRead(op: WearCommOp): Boolean {
            calls += "voicemail:${op.targetKey}"
            return true
        }

        override suspend fun declineCall(op: WearCommOp): Boolean {
            calls += "decline:${op.targetKey}"
            return true
        }
    }

    @Test
    fun quickReplyReachesHandlerTrimmed() = runBlocking {
        val handler = FakeHandler()
        val op = WearCommOp(
            opId = "1", kind = WearOpKind.QuickReply, threadKey = "Sim:3",
            line = WearCommLine.Sim, body = "  OK  ",
        )
        assertTrue(applyWearCommOp(op, handler))
        assertEquals(listOf("reply:Sim:3:OK"), handler.calls)
    }

    @Test
    fun blankReplyAndMissingTargetsNeverReachHandler() = runBlocking {
        val handler = FakeHandler()
        assertFalse(
            applyWearCommOp(
                WearCommOp(opId = "1", kind = WearOpKind.QuickReply, threadKey = "Sim:3", body = " "),
                handler,
            ),
        )
        assertFalse(
            applyWearCommOp(WearCommOp(opId = "2", kind = WearOpKind.MarkRead), handler),
        )
        assertFalse(
            applyWearCommOp(WearCommOp(opId = "3", kind = WearOpKind.DeclineCall), handler),
        )
        assertFalse(
            applyWearCommOp(WearCommOp(opId = "4", kind = WearOpKind.MarkVoicemailRead), handler),
        )
        assertEquals(emptyList(), handler.calls)
    }

    @Test
    fun markReadVoicemailAndDeclineDispatch() = runBlocking {
        val handler = FakeHandler()
        assertTrue(
            applyWearCommOp(
                WearCommOp(opId = "1", kind = WearOpKind.MarkRead, threadKey = "Signal:c1"),
                handler,
            ),
        )
        assertTrue(
            applyWearCommOp(
                WearCommOp(
                    opId = "2", kind = WearOpKind.MarkVoicemailRead, targetKey = "Sim:9",
                ),
                handler,
            ),
        )
        assertTrue(
            applyWearCommOp(
                WearCommOp(
                    opId = "3", kind = WearOpKind.DeclineCall, targetKey = "WhatsApp:peer",
                ),
                handler,
            ),
        )
        assertEquals(
            listOf("read:Signal:c1", "voicemail:Sim:9", "decline:WhatsApp:peer"),
            handler.calls,
        )
    }

    @Test
    fun handlerFailurePropagatesAsFailure() = runBlocking {
        val handler = FakeHandler(replyOk = false)
        assertFalse(
            applyWearCommOp(
                WearCommOp(
                    opId = "1", kind = WearOpKind.QuickReply, threadKey = "Sim:3", body = "OK",
                ),
                handler,
            ),
        )
    }
}
