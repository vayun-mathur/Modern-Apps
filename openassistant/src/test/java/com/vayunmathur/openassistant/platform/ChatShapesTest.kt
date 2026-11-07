package com.vayunmathur.openassistant.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ChatRequest/ChatResult/HistoryTurn value shapes (engine itself is native-bound). */
class ChatShapesTest {
    @Test fun requestDefaults() {
        val req = ChatRequest(system = "s", history = listOf(HistoryTurn(true, "a"), HistoryTurn(false, "b")), userText = "c")
        assertEquals(2, req.history.size)
        assertEquals(emptyList(), req.imagePaths)
        assertNull(req.audioPath)
        assertTrue(req.history[0].fromUser)
        assertTrue(!req.history[1].fromUser)
    }

    @Test fun resultVariants() {
        assertTrue(ChatResult.Reply("x", halted = true).halted)
        assertEquals("x", ChatResult.Reply("x", halted = false).text)
        assertEquals(ChatResult.TooLong, ChatResult.TooLong)
        assertEquals(ChatResult.Unavailable, ChatResult.Unavailable)
    }
}
