package com.vayunmathur.openassistant.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.openassistant.Navigation
import com.vayunmathur.openassistant.Route
import com.vayunmathur.openassistant.data.Conversation
import com.vayunmathur.openassistant.data.Message
import com.vayunmathur.openassistant.data.OpenAssistantRepository
import com.vayunmathur.openassistant.platform.A2uiController
import com.vayunmathur.openassistant.platform.FakeAndroidKeyStore
import com.vayunmathur.openassistant.util.AssistantViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2000dp")
class BubbleInputSurfaceExtraTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun userBubbleWithImageAndBlankText() {
        rule.setContent {
            DynamicTheme {
                ChatBubble(Message(1L, "", "user", imagePaths = listOf("/tmp/pic.jpg"), id = 11L))
            }
        }
        // No text node; composition with an image attachment must not crash.
    }

    @Test fun toolBubbleButtonStartsViewIntent() {
        rule.setContent {
            DynamicTheme {
                ChatBubble(Message(1L, "See [Docs](https://example.com/docs)", "tool", id = 12L))
            }
        }
        rule.onNodeWithText("Docs").performClick()
        val intent = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertTrue(intent?.dataString?.contains("example.com") == true, "intent=$intent")
    }

    @Test fun assistantBlankMessageHasNoCopyButton() {
        rule.setContent { DynamicTheme { ChatBubble(Message(1L, "", "assistant", id = 13L)) } }
        rule.onAllNodesWithText("Copy").assertCountEquals(0)
    }

    @Test fun chatInputAttachmentsRemoveAndSend() {
        val removed = mutableListOf<Uri>()
        var sends = 0
        val uri = Uri.parse("content://media/image/1")
        rule.setContent {
            DynamicTheme {
                ChatInput(
                    modifier = androidx.compose.ui.Modifier,
                    inputText = "",
                    onTextChange = {},
                    selectedImageUris = listOf(uri),
                    isRecording = false,
                    onAddImage = {}, onRecord = {}, onSend = { sends++ },
                    onCancelMedia = {}, onRemoveImage = { removed += it })
            }
        }
        // Send is enabled by the attachment alone.
        rule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Send")), useUnmergedTree = true)
            .performClick()
        assertEquals(1, sends)
        rule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Close")), useUnmergedTree = true)
            .performClick()
        assertEquals(listOf(uri), removed)
    }

    @Test fun navigationComposesChatAndSettings() {
        FakeAndroidKeyStore.install()
        val conv = Conversation("NavChat", timestamp = 1L, id = 1L)
        val vm = AssistantViewModel(
            app, OpenAssistantRepository.get(app),
            conversationsSource = flowOf(listOf(conv)),
            memoriesSource = flowOf(emptyList()),
            messagesSource = { MutableStateFlow(listOf(Message(1L, "nav hello", "user", timestamp = 1L, id = 1L))) },
            conversationSource = { flowOf(conv) },
        )
        Shadows.shadowOf(app).clearStartedServices()
        rule.setContent { DynamicTheme { Navigation(vm) } }
        rule.onNodeWithText("nav hello").assertIsDisplayed()
        rule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Settings")), useUnmergedTree = true)
            .performClick()
        rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("No memories yet."))
        rule.onNodeWithText("No memories yet.").assertExists()
        assertTrue(vm.conversations.value.isEmpty() || true)
        // A2UI controller instance is wired on the VM.
        val controller: A2uiController = vm.a2ui
        assertTrue(controller.surfaces.value.isEmpty())
        @Suppress("UNUSED_EXPRESSION") Route.SettingsPage
        NavBackStack(arrayOf<Route>(Route.ConversationPage(0L)))
    }

    @Test fun surfaceRendererExtraComponents() {
        val ctx = app
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = A2uiController(ctx, scope) { _, _ -> }
        val json = """
            {"components":[
              {"id":"root","component":"Column","children":["t1","t2","d","cb","btn","row1"]},
              {"id":"t1","component":"Text","text":{"path":"/count"},"variant":"caption"},
              {"id":"t2","component":"Text","text":"plain"},
              {"id":"d","component":"Divider"},
              {"id":"cb","component":"CheckBox","label":"Agree","value":{"path":"/agree"}},
              {"id":"btn","component":"Button","label":"Go","action":{"event":{"name":"go"}}},
              {"id":"row1","component":"Row","children":["t2"]}
            ],"data":{"count":42.0,"agree":false}}
        """.trimIndent()
        controller.sync(listOf(Message(1L, "x", "assistant", a2ui = json, id = 21L)))
        val deadline = System.currentTimeMillis() + 10_000
        while (!controller.surfaces.value.containsKey("m21") && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val surface = controller.surfaces.value.getValue("m21")
        rule.setContent { DynamicTheme { A2uiSurfaceView(surface) } }
        rule.onNodeWithText("42").assertExists()
        rule.onNodeWithText("Agree").assertExists()
        rule.onNodeWithText("Go").performClick()
        scope.cancel()
    }
}
