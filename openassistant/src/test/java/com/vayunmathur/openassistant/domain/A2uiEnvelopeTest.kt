package com.vayunmathur.openassistant.domain

import androidx.a2ui.model.protocol.A2uiComponentPayload
import androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage
import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage
import androidx.a2ui.model.protocol.A2uiUpdateDataModelMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class A2uiEnvelopeTest {

    @Test
    fun wrapsComponentsAndDataInAnEnvelope() {
        val json = """
            {"components":[
              {"id":"root","component":"Column","children":["t","f"]},
              {"id":"t","component":"Text","text":"Hi"},
              {"id":"f","component":"TextField","label":"Name","value":{"path":"/name"}}
            ],"data":{"name":"Ada","count":2,"ratio":0.5,"ok":true,"none":null}}
        """.trimIndent()

        val messages = buildA2uiEnvelope("m7", "cat", json)!!

        assertEquals(A2uiCreateSurfaceMessage("m7", "cat"), messages[0])
        assertEquals(
            A2uiUpdateComponentsMessage(
                "m7",
                listOf(
                    A2uiComponentPayload("root", "Column", mapOf("children" to listOf("t", "f"))),
                    A2uiComponentPayload("t", "Text", mapOf("text" to "Hi")),
                    A2uiComponentPayload("f", "TextField", mapOf("label" to "Name", "value" to mapOf("path" to "/name"))),
                ),
            ),
            messages[1],
        )
        assertEquals(
            A2uiUpdateDataModelMessage(
                "m7",
                "/",
                mapOf("name" to "Ada", "count" to 2L, "ratio" to 0.5, "ok" to true, "none" to null),
            ),
            messages[2],
        )
    }

    @Test
    fun acceptsBareComponentArrayAndSkipsBadEntries() {
        val messages = buildA2uiEnvelope("m1", "cat", """[{"id":"root","component":"Text","text":"x"},{"id":"bad"},3]""")!!
        assertEquals(2, messages.size)
        assertEquals(1, (messages[1] as A2uiUpdateComponentsMessage).components.size)
    }

    @Test
    fun rejectsUnusableBlocks() {
        assertNull(buildA2uiEnvelope("m1", "cat", "{not json"))
        assertNull(buildA2uiEnvelope("m1", "cat", """{"components":[]}"""))
        assertNull(buildA2uiEnvelope("m1", "cat", """{"data":{}}"""))
        assertNull(buildA2uiEnvelope("m1", "cat", "\"text\""))
    }

    @Test
    fun formatsActionTurns() {
        assertEquals("[UI action \"cancel\"]", actionTurnText("cancel", emptyMap()))
        assertEquals(
            "[UI action \"rsvp\": {\"name\":\"Ada\",\"veg\":true,\"guests\":[1,2]}]",
            actionTurnText("rsvp", linkedMapOf("name" to "Ada", "veg" to true, "guests" to listOf(1, 2))),
        )
    }

    @Test
    fun surfaceIdIsPerMessage() {
        assertEquals("m42", a2uiSurfaceId(42))
    }
}
