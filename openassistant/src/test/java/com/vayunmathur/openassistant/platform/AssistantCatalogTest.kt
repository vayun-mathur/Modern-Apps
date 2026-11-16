package com.vayunmathur.openassistant.platform

import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.engine.processor.A2uiCoreMessageProcessor
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiDataPath
import com.vayunmathur.openassistant.domain.A2UI_FENCE_OPEN
import com.vayunmathur.openassistant.domain.buildA2uiEnvelope
import com.vayunmathur.openassistant.domain.splitA2ui
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantCatalogTest {

    private val catalog = AssistantCatalog({}, { pattern, _, _ -> pattern })

    private fun processor() = A2uiCoreMessageProcessor(listOf(catalog), ::ComposeDataModel, ::ComposeComponentRegistry)

    private suspend fun A2uiCoreMessageProcessor.awaitSurface(): A2uiCoreSurfaceModel =
        withTimeout(TIMEOUT) { activeSurfaces.first { it.isNotEmpty() }.single() as A2uiCoreSurfaceModel }

    private suspend fun ComposeComponentRegistry.awaitSettled(count: Int) =
        withTimeout(TIMEOUT) { while (components.size + errors.size < count) delay(5) }

    @Test
    fun promptExampleRendersWithoutErrors() = runBlocking {
        val processor = processor()
        val loop = launch { processor.collectMessages() }
        buildA2uiEnvelope("m1", AssistantCatalog.ID, AssistantCatalog.EXAMPLE)!!.forEach(processor::processMessage)

        val surface = processor.awaitSurface()
        val registry = surface.componentRegistry as ComposeComponentRegistry
        registry.awaitSettled(6)

        assertEquals(setOf("root", "c", "q", "n", "v", "b"), registry.components.keys)
        assertTrue(registry.errors.isEmpty(), "errors: ${registry.errors}")
        assertEquals(false, surface.dataModel[A2uiDataPath("/veg")])
        loop.cancel()
    }

    @Test
    fun invalidComponentsFailAloneAndReportErrors() = runBlocking {
        val processor = processor()
        val loop = launch { processor.collectMessages() }
        val firstError = async(start = CoroutineStart.UNDISPATCHED) {
            processor.outboundEvents.filterIsInstance<A2uiClientErrorMessage>().first()
        }
        val json = """{"components":[
            {"id":"root","component":"Column","children":["ok","bad","nolabel"]},
            {"id":"ok","component":"Text","text":"fine"},
            {"id":"bad","component":"Spinner"},
            {"id":"nolabel","component":"Button","action":{"event":{"name":"go"}}}
        ]}"""
        buildA2uiEnvelope("m2", AssistantCatalog.ID, json)!!.forEach(processor::processMessage)

        val registry = processor.awaitSurface().componentRegistry as ComposeComponentRegistry
        registry.awaitSettled(4)

        assertEquals(setOf("root", "ok"), registry.components.keys)
        assertEquals(setOf("bad", "nolabel"), registry.errors.keys)
        assertEquals("m2", withTimeout(TIMEOUT) { firstError.await() }.surfaceId)
        loop.cancel()
    }

    @Test
    fun buttonEventCarriesResolvedContext() = runBlocking {
        val processor = processor()
        val loop = launch { processor.collectMessages() }
        val event = async(start = CoroutineStart.UNDISPATCHED) {
            processor.outboundEvents.filterIsInstance<A2uiClientEventMessage>().first()
        }
        buildA2uiEnvelope("m3", AssistantCatalog.ID, AssistantCatalog.EXAMPLE)!!.forEach(processor::processMessage)
        val surface = processor.awaitSurface()
        (surface.componentRegistry as ComposeComponentRegistry).awaitSettled(6)

        surface.dataModel.update(A2uiDataPath("/name"), "Ada")
        @Suppress("UNCHECKED_CAST")
        val action = surface.componentRegistry.let { it as ComposeComponentRegistry }
            .components.getValue("b").properties["action"] as Map<String, Any?>
        surface.dispatchAction("b", action)

        val sent = withTimeout(TIMEOUT) { event.await() }
        assertEquals("rsvp", sent.type)
        assertEquals(mapOf("name" to "Ada", "veg" to false), sent.context)
        loop.cancel()
    }

    @Test
    fun pickerStepperAndMultilineTextFieldRenderWithoutErrors() = runBlocking {
        val processor = processor()
        val loop = launch { processor.collectMessages() }
        val json = """{"components":[
            {"id":"root","component":"Column","children":["p","s","t"]},
            {"id":"p","component":"Picker","label":"Pick one",
             "options":{"path":"/items"},"value":{"path":"/selected"},
             "action":{"event":{"name":"pick.item","context":{"id":{"path":"/selected"}}}}},
            {"id":"s","component":"QuantityStepper","label":"Qty",
             "value":{"path":"/qty"},"min":1,"max":5},
            {"id":"t","component":"TextField","label":"Notes","value":{"path":"/notes"},"multiline":true}
        ],"data":{"items":[{"id":"a","title":"A"},{"id":"b","title":"B"}],"selected":"a","qty":2,"notes":""}}"""
        buildA2uiEnvelope("m9", AssistantCatalog.ID, json)!!.forEach(processor::processMessage)
        val surface = processor.awaitSurface()
        val registry = surface.componentRegistry as ComposeComponentRegistry
        registry.awaitSettled(4)
        assertEquals(setOf("root", "p", "s", "t"), registry.components.keys)
        assertTrue(registry.errors.isEmpty(), "errors: ${registry.errors}")
        assertEquals(2L, surface.dataModel[A2uiDataPath("/qty")])
        // Prompt lines for the new components are generated from the same definitions.
        assertTrue(AssistantCatalog.PROMPT_SECTION.contains("- Picker: "))
        assertTrue(AssistantCatalog.PROMPT_SECTION.contains("- QuantityStepper: "))
        assertTrue(AssistantCatalog.PROMPT_SECTION.contains("multiline"))
        assertTrue(AssistantCatalog.PROMPT_SECTION.contains("render_ui"))
        loop.cancel()
    }

    @Test
    fun promptListsEveryComponentAndItsExampleParses() {
        AssistantCatalog.COMPONENT_NAMES.forEach {
            assertTrue(AssistantCatalog.PROMPT_SECTION.contains("- $it: "), "prompt is missing $it")
        }
        val example = AssistantCatalog.PROMPT_SECTION.substringAfterLast(A2UI_FENCE_OPEN)
        assertEquals(AssistantCatalog.EXAMPLE, splitA2ui(A2UI_FENCE_OPEN + example, streaming = false).json)
    }

    @Test
    fun dataModelWritesCopyPathsAndDeleteOnNull() {
        val data = ComposeDataModel()
        data.update(A2uiDataPath("/a/b"), 1L)
        data.update(A2uiDataPath("/list"), listOf("x"))
        data.update(A2uiDataPath("/list/1"), "y")
        assertEquals(mapOf("a" to mapOf("b" to 1L), "list" to listOf("x", "y")), data[A2uiDataPath("/")])
        data.update(A2uiDataPath("/a/b"), null)
        assertEquals(emptyMap<String, Any?>(), data[A2uiDataPath("/a")])
        assertEquals(null, data[A2uiDataPath("/missing/deep")])
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
