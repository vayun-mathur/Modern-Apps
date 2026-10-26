package com.vayunmathur.openassistant.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins tool-call parsing and dispatch gating for assistant tool use.
 *
 * Model output arrives as streaming text with an embedded JSON call, so the
 * host must extract the largest balanced object, reject malformed payloads
 * without throwing, and dispatch only allowlisted tools. Mirrors the
 *(JsonExtractor plus runTool gating) contract without the model or intents.
 */
class ToolCallParseTest {

    private data class ToolCall(val name: String, val args: Map<String, String>)

    private val allowed = setOf(
        "getNotes", "createNote", "getContacts", "setAlarm", "setTimer",
        "getWeather", "addToMemory", "getMemories", "removeMemory",
    )

    private fun largestObject(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun parseCall(json: String): ToolCall? {
        val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: return null
        val args = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(json)
            .filter { it.groupValues[1] != "name" }
            .associate { it.groupValues[1] to it.groupValues[2] }
        return ToolCall(name, args)
    }

    private fun dispatch(call: ToolCall?): String {
        if (call == null) return "Error: malformed tool call"
        if (call.name !in allowed) return "Error: unknown tool '${call.name}'"
        return "ok:${call.name}"
    }

    @Test
    fun emptyStreamHasNoObject() {
        assertNull(largestObject(""))
        assertNull(largestObject("just thinking out loud"))
    }

    @Test
    fun preambleAndPostambleAreIgnored() {
        val text = "Sure! {\"name\": \"getNotes\"} hope that helps"
        assertEquals("{\"name\": \"getNotes\"}", largestObject(text))
    }

    @Test
    fun bracesInsideStringsDoNotCloseTheObject() {
        val text = "{\"name\": \"createNote\", \"title\": \"a } b\"}"
        assertEquals(text, largestObject("xo $text tail"))
    }

    @Test
    fun unbalancedBracesYieldNull() {
        assertNull(largestObject("{\"name\": \"getNotes\""))
        assertNull(largestObject("{\"a\": {\"b\": 1}"))
    }

    @Test
    fun malformedCallsNeverThrowAndReportError() {
        assertEquals("Error: malformed tool call", dispatch(null))
        assertEquals("Error: malformed tool call", dispatch(parseCall("{}")))
        assertEquals("Error: malformed tool call", dispatch(parseCall("{\"title\": \"x\"}")))
    }

    @Test
    fun unknownToolIsRejected() {
        val call = parseCall("{\"name\": \"deleteEverything\", \"x\": \"1\"}")
        assertEquals("Error: unknown tool 'deleteEverything'", dispatch(call))
    }

    @Test
    fun allowlistedToolsDispatch() {
        for (name in allowed) {
            assertEquals("ok:$name", dispatch(ToolCall(name, emptyMap())))
            assertTrue(dispatch(ToolCall(name, mapOf("a" to "b"))).startsWith("ok:"))
        }
    }

    @Test
    fun caseSensitiveNamesDoNotMatch() {
        assertEquals("Error: unknown tool 'GetNotes'", dispatch(ToolCall("GetNotes", emptyMap())))
        assertEquals("Error: unknown tool ''", dispatch(ToolCall("", emptyMap())))
    }
}
