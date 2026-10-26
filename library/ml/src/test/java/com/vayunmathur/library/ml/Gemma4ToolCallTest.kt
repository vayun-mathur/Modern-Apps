package com.vayunmathur.library.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Gemma4ToolCallTest {

    private val q = GEMMA4_QUOTE

    @Test
    fun quotedStrings() {
        val call = parseGemma4ToolCall("<|tool_call>call:create_note{content:${q}a, b: c${q},title:${q}x${q}}<tool_call|>")
        assertEquals(Gemma4Handle.ToolCall("create_note", mapOf("content" to "a, b: c", "title" to "x")), call)
    }

    @Test
    fun bareNumbersAndBooleans() {
        val call = parseGemma4ToolCall("<|tool_call>call:set_alarm{hour:7,minute: 30 ,loud:true}<tool_call|>")
        assertEquals(mapOf("hour" to "7", "minute" to "30", "loud" to "true"), call?.arguments)
    }

    @Test
    fun mixedQuotedAndBare() {
        val call = parseGemma4ToolCall("Sure.<|tool_call>call:set_timer{label:${q}tea${q},seconds:120}<tool_call|>")
        assertEquals(Gemma4Handle.ToolCall("set_timer", mapOf("label" to "tea", "seconds" to "120")), call)
        val reversed = parseGemma4ToolCall("<|tool_call>call:set_timer{seconds:-1.5,label:${q}x${q}}<tool_call|>")
        assertEquals(mapOf("seconds" to "-1.5", "label" to "x"), reversed?.arguments)
    }

    @Test
    fun noArguments() {
        assertEquals(Gemma4Handle.ToolCall("get_notes", emptyMap()), parseGemma4ToolCall("<|tool_call>call:get_notes{}<tool_call|>"))
    }

    @Test
    fun incompleteOrNamelessCallsDoNotParse() {
        assertNull(parseGemma4ToolCall("<|tool_call>call:set_timer{seconds:12"))
        assertNull(parseGemma4ToolCall("<|tool_call>call:{seconds:1}<tool_call|>"))
        assertNull(parseGemma4ToolCall("<|tool_call>call:oops<tool_call|>"))
        assertNull(parseGemma4ToolCall("plain text"))
    }

    @Test
    fun responseRoundTripsTheQuoteMarker() {
        assertEquals("<|tool_response>response:f{value:${q}ok${q}}<tool_response|>", renderGemma4ToolResponse("f", "ok"))
    }
}
