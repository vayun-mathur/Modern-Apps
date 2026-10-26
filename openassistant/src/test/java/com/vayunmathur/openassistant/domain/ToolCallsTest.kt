package com.vayunmathur.openassistant.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolCallsTest {

    private val q = "<|\"|>"

    @Test
    fun detectsCompleteCallsAndGuessesNames() {
        assertTrue(hasCompleteToolCall("Sure.<|tool_call>call:set_timer{seconds:120}<tool_call|>"))
        assertFalse(hasCompleteToolCall("<|tool_call>call:set_timer{seconds:12"))
        assertFalse(hasCompleteToolCall("plain text"))
        assertEquals("x", toolCallNameGuess("<|tool_call>call:x{label:${q}open}<tool_call|>"))
        assertEquals("oops", toolCallNameGuess("<|tool_call>call:oops<tool_call|>"))
        assertEquals("unknown", toolCallNameGuess("<|tool_call>call:{}<tool_call|>"))
    }

    @Test
    fun stripsCompleteBlocksAndUnclosedTails() {
        val reply = "Hello <|tool_call>call:a{}<tool_call|>world"
        assertEquals("Hello world", stripToolMarkup(reply))
        assertEquals("Let me check", stripToolMarkup("Let me check<|tool_call>call:get_no"))
        assertEquals("A", stripToolMarkup("A<|tool_response>response:x{value:1}"))
    }

    @Test
    fun hidesHalfWrittenMarkersOnlyWhileStreaming() {
        assertEquals("Hi ", stripToolMarkup("Hi <|too"))
        assertEquals("Hi ", stripToolMarkup("Hi <"))
        assertEquals("Hi <", stripToolMarkup("Hi <", streaming = false))
        assertEquals("a <b", stripToolMarkup("a <b"))
        assertEquals("x < y and more", stripToolMarkup("x < y and more"))
    }

    @Test
    fun coercesNumbersAndReportsBadArguments() {
        val spec = ASSISTANT_TOOL_SPECS.first { it.name == "set_timer" }
        val ok = assertIs<ToolArgsResult.Ok>(coerceToolArgs(spec, mapOf("seconds" to " 90.0 ")))
        assertEquals(90.0, ok.args.number("seconds"))
        assertEquals("", ok.args.string("label"))
        val bad = assertIs<ToolArgsResult.Invalid>(coerceToolArgs(spec, mapOf("seconds" to "soon")))
        assertTrue(bad.message.startsWith("Error:"))
        assertIs<ToolArgsResult.Invalid>(coerceToolArgs(spec, emptyMap()))
        assertIs<ToolArgsResult.Invalid>(coerceToolArgs(spec, mapOf("seconds" to "")))
    }
}
