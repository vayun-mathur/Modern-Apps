package com.vayunmathur.openassistant.platform

import androidx.a2ui.model.protocol.A2uiDataPath
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.ui.A2uiSurfaceView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A2uiController sync + SurfaceRenderer rendering of a catalog surface. */
@RunWith(RobolectricTestRunner::class)
class A2uiSurfaceRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val json = """
        {"components":[
          {"id":"root","component":"Card","child":"c"},
          {"id":"c","component":"Column","children":["q","n","v","b"]},
          {"id":"q","component":"Text","text":"Who's coming?","variant":"title"},
          {"id":"n","component":"TextField","label":"Name","value":{"path":"/name"}},
          {"id":"v","component":"CheckBox","label":"Vegetarian","value":{"path":"/veg"}},
          {"id":"b","component":"Button","label":"RSVP","primary":true,
            "action":{"event":{"name":"rsvp","context":{"name":{"path":"/name"},"veg":{"path":"/veg"}}}}}
        ],"data":{"name":"","veg":false}}
    """.trimIndent()

    private fun await(timeoutMs: Long = 10_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test fun controllerFeedsSurfaceAndRendererDrawsIt() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actions = mutableListOf<Pair<Long, String>>()
        val controller = A2uiController(ctx, scope) { id, text -> actions.add(id to text) }
        try {
            controller.sync(listOf(Message(9L, "hi", "assistant", a2ui = json, id = 7L)))
            assertTrue(await { controller.surfaces.value.containsKey("m7") },
                "surfaces=${controller.surfaces.value.keys}")
            // Idempotent: syncing the same message again feeds nothing new.
            controller.sync(listOf(Message(9L, "hi", "assistant", a2ui = json, id = 7L)))
            assertEquals(1, controller.surfaces.value.size)
            // A message without a block and a bad block are ignored.
            controller.sync(listOf(
                Message(9L, "plain", "assistant", id = 8L),
                Message(9L, "bad", "assistant", a2ui = "{nope", id = 9L)))
            assertEquals(1, controller.surfaces.value.size)

            val surface = controller.surfaces.value.getValue("m7")
            composeRule.setContent { DynamicTheme { A2uiSurfaceView(surface) } }
            composeRule.onNodeWithText("Who's coming?").assertIsDisplayed()
            composeRule.onNodeWithText("Name").assertIsDisplayed()
            composeRule.onNodeWithText("Vegetarian").assertIsDisplayed()
            composeRule.onNodeWithText("RSVP").assertIsDisplayed()
            // Typing into the bound field updates the data model; the button
            // then reports the typed value back through onAction.
            composeRule.onNode(hasSetTextAction()).performTextInput("Ada")
            composeRule.onNodeWithText("RSVP").performClick()
            assertTrue(await { actions.isNotEmpty() }, "actions=$actions")
            assertEquals(9L, actions[0].first)
            assertTrue(actions[0].second.contains("Ada"), actions[0].second)
        } finally {
            scope.cancel()
        }
    }

    @Test fun dataModelPathWritesAndReads() {
        val model = ComposeDataModel()
        model.update(A2uiDataPath("/name"), "Ada")
        model.update(A2uiDataPath("/items/0/title"), "First")
        model.update(A2uiDataPath("/items/1/title"), "Second")
        assertEquals("Ada", model[A2uiDataPath("/name")])
        assertEquals("First", model[A2uiDataPath("/items/0/title")])
        assertEquals("Second", model[A2uiDataPath("/items/1/title")])
        assertNull(model[A2uiDataPath("/missing")])
        assertNull(model[A2uiDataPath("/items/9/title")])
        // Overwriting a leaf and clearing the model.
        model.update(A2uiDataPath("/name"), "Grace")
        assertEquals("Grace", model[A2uiDataPath("/name")])
        model.close()
        assertNull(model[A2uiDataPath("/name")])
    }

    @Test fun componentRegistryTracksErrors() {
        val registry = ComposeComponentRegistry()
        val payload = androidx.a2ui.model.protocol.A2uiComponentPayload(
            "root", "Text", mapOf("text" to "Hi"))
        registry.update(listOf(payload))
        assertEquals(payload, registry.components["root"])
        val exception = org.mockito.Mockito.mock(
            androidx.a2ui.model.protocol.A2uiException::class.java)
        org.mockito.Mockito.`when`(exception.message).thenReturn("bad prop")
        org.mockito.Mockito.`when`(exception.code).thenReturn("INVALID_ARGUMENT")
        registry.reportError("root", exception)
        assertTrue(registry.errors.containsKey("root"))
        // A fresh update clears the component's error.
        registry.update(listOf(payload))
        assertTrue(registry.errors.isEmpty())
        registry.close()
        assertTrue(registry.components.isEmpty())
    }
}
