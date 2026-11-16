package com.vayunmathur.openassistant.platform

import com.vayunmathur.openassistant.domain.PendingToolCall
import com.vayunmathur.openassistant.domain.buildA2uiEnvelope
import com.vayunmathur.openassistant.domain.redactedArgsSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class A2uiTemplatesTest {

    @Test fun confirmCardIsEnvelopeValid() {
        val pending = PendingToolCall(
            id = "p-123",
            name = "create_note",
            args = mapOf("title" to "T", "content" to "hello world!"),
            summary = "create_note: title=T, content=hello world!",
            createdAtMillis = 1_000L,
        )
        val json = A2uiTemplates.confirmCard(pending)
        assertTrue(json.contains("confirm.tool"), json)
        assertTrue(json.contains("cancel.tool"), json)
        assertTrue(json.contains("Confirm create_note"), json)
        assertTrue(json.contains("p-123"), json)
        val envelope = buildA2uiEnvelope("m1", AssistantCatalog.ID, json)
        assertNotNull(envelope)
        assertEquals(3, envelope.size)
    }

    @Test fun redactedSummaryShowsLengthsOnly() {
        assertEquals(
            "content(len=12), title(len=1)",
            redactedArgsSummary(linkedMapOf("content" to "hello world!", "title" to "T")),
        )
        assertEquals("(no args)", redactedArgsSummary(emptyMap()))
    }
}
