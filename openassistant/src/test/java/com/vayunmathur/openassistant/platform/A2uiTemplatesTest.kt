package com.vayunmathur.openassistant.platform

import com.vayunmathur.openassistant.domain.PendingToolCall
import com.vayunmathur.openassistant.domain.buildA2uiEnvelope
import com.vayunmathur.openassistant.domain.redactedArgsSummary
import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.engine.processor.A2uiCoreMessageProcessor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private fun componentsOf(json: String): Map<String, Map<String, String>> {
        val root = Json.parseToJsonElement(json).jsonObject
        return root.getValue("components").jsonArray.associate { el ->
            val obj = el.jsonObject
            obj.getValue("id").jsonPrimitive.content to
                obj.mapValues { it.value.toString().trim('"') }
        }
    }

    private fun assertEnvelopeValid(json: String) {
        val envelope = buildA2uiEnvelope("m1", AssistantCatalog.ID, json)
        assertNotNull(envelope, json)
        assertEquals(3, envelope.size, json)
    }

    @Test fun listPickerIsEnvelopeValid() {
        val json = A2uiTemplates.listPicker(
            "Choose a place",
            listOf(
                PickerItem("r1", "Sushi Place", "4.8 stars", "https://example.com/s.png"),
                PickerItem("r2", "Taco Place"),
            ),
        )
        assertEnvelopeValid(json)
        val comps = componentsOf(json)
        assertEquals("Card", comps.getValue("root")["component"])
        assertEquals("Picker", comps.getValue("picker")["component"])
        assertTrue(json.contains("pick.item"), json)
        assertTrue(json.contains("Sushi Place"), json)
        assertTrue(json.contains("/items"), json)
        // Custom action name is honoured.
        assertTrue(A2uiTemplates.listPicker("T", listOf(PickerItem("x", "X")), "pick.restaurant").contains("pick.restaurant"))
    }

    @Test fun statusAndResultCardsAreEnvelopeValid() {
        val status = A2uiTemplates.statusCard("Order placed", "Arriving at 7pm", variant = "success")
        assertEnvelopeValid(status)
        assertEquals("Text", componentsOf(status).getValue("title")["component"])
        assertTrue(status.contains("Order placed") && status.contains("success"), status)

        val plain = A2uiTemplates.resultCard("Done", listOf("Line one", "Line two"))
        assertEnvelopeValid(plain)
        assertTrue(plain.contains("Line one") && plain.contains("Line two"), plain)
        assertTrue(!plain.contains("btnAction"), plain)

        val withAction = A2uiTemplates.resultCard(
            "Done", listOf("Line"), actionLabel = "Open", actionName = "open.app",
            actionContext = mapOf("id" to "42"),
        )
        assertEnvelopeValid(withAction)
        val comps = componentsOf(withAction)
        assertEquals("Button", comps.getValue("btnAction")["component"])
        assertTrue(withAction.contains("open.app") && withAction.contains("42"), withAction)
    }

    @Test fun searchResultsAreEnvelopeValid() {
        val json = A2uiTemplates.searchResults(
            "best sushi",
            listOf(
                SearchResultItem(1, "Sushi Guide", "All about sushi", "example.com", "https://example.com/sushi"),
                SearchResultItem(2, "Taco Guide", "All about tacos", "tacos.example", "https://tacos.example/"),
            ),
        )
        assertEnvelopeValid(json)
        val comps = componentsOf(json)
        assertEquals("List", comps.getValue("results")["component"])
        assertEquals("Row", comps.getValue("result")["component"])
        assertEquals("Column", comps.getValue("resultBody")["component"])
        assertEquals("Button", comps.getValue("btnRead")["component"])
        assertEquals("Button", comps.getValue("btnOpen")["component"])
        assertTrue(json.contains("web.read"), json)
        assertTrue(json.contains("web.open"), json)
        assertTrue(json.contains("[1] Sushi Guide"), json)
        assertTrue(json.contains("best sushi"), json)
    }

    @Test fun templatesValidateAgainstCatalog() = runBlocking {
        val fixtures = listOf(
            A2uiTemplates.listPicker("Pick", listOf(PickerItem("a", "Alpha", "cap"))),
            A2uiTemplates.statusCard("Done", "All good", "success"),
            A2uiTemplates.resultCard("Done", listOf("One"), "Open", "open.app", mapOf("id" to "1")),
            A2uiTemplates.searchResults(
                "q",
                listOf(SearchResultItem(1, "T", "S", "example.com", "https://example.com/")),
            ),
        )
        for ((index, json) in fixtures.withIndex()) {
            val catalog = AssistantCatalog({}, { pattern, _, _ -> pattern })
            val processor = A2uiCoreMessageProcessor(listOf(catalog), ::ComposeDataModel, ::ComposeComponentRegistry)
            val loop = launch { processor.collectMessages() }
            try {
                val envelope = buildA2uiEnvelope("t$index", AssistantCatalog.ID, json)
                assertNotNull(envelope)
                envelope.forEach(processor::processMessage)
                withTimeout(5_000) {
                    while (true) {
                        val surfaces = processor.activeSurfaces.value
                        val registry = (surfaces.firstOrNull() as? A2uiCoreSurfaceModel)
                            ?.componentRegistry as? ComposeComponentRegistry
                        if (registry != null && registry.components.isNotEmpty()) break
                        delay(10)
                    }
                }
                // Give validation a moment to report any per-component errors.
                delay(100)
                val registry = (processor.activeSurfaces.value.first() as A2uiCoreSurfaceModel).componentRegistry as ComposeComponentRegistry
                assertTrue(registry.errors.isEmpty(), "fixture $index errors: ${registry.errors} json=$json")
            } finally {
                loop.cancel()
            }
        }
    }

    @Test fun redactedSummaryShowsLengthsOnly() {
        assertEquals(
            "content(len=12), title(len=1)",
            redactedArgsSummary(linkedMapOf("content" to "hello world!", "title" to "T")),
        )
        assertEquals("(no args)", redactedArgsSummary(emptyMap()))
    }
}
