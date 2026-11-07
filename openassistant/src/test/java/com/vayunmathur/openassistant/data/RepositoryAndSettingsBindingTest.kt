package com.vayunmathur.openassistant.data

import android.app.Application
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.openassistant.Route
import com.vayunmathur.openassistant.platform.FakeAndroidKeyStore
import com.vayunmathur.openassistant.ui.SettingsPage
import com.vayunmathur.openassistant.util.AssistantViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h2000dp")
class RepositoryAndSettingsBindingTest {
    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val memory = Memory("lives in Lisbon", id = 8L)
    private val deleted = mutableListOf<Memory>()

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        deleted.clear()
    }

    @Test fun repositoryDelegatesToDaos() {
        val repo = OpenAssistantRepository.get(app)
        // Flow getters build DAO flows without opening the encrypted database.
        assertNotNull(repo.conversationsFlow())
        assertNotNull(repo.conversationByIdFlow(1L))
        assertNotNull(repo.messagesForConversationFlow(1L))
        assertNotNull(repo.memoriesFlow())
        assertNotNull(repo.conversationDaoRef)
        assertNotNull(repo.messageDaoRef)
        assertNotNull(repo.memoryDaoRef)
        // Suspending queries cannot open SQLCipher on the JVM: they must fail
        // here (and work on device), proving the delegation reaches Room.
        val attempts: List<() -> Unit> = listOf(
            { runBlocking { repo.getConversation(1L) } },
            { runBlocking { repo.getMessagesForConversation(1L) } },
            { runBlocking { repo.getMessage(1L) } },
            { runBlocking { repo.getAllMemories() } },
            { runBlocking { repo.upsertConversation(Conversation("x")) } },
            { runBlocking { repo.upsertMessage(Message(1L, "x", "user")) } },
            { runBlocking { repo.upsertMemory(Memory("x")) } },
            { runBlocking { repo.deleteConversation(Conversation("x", id = 1L)) } },
            { runBlocking { repo.deleteMessage(Message(1L, "x", "user", id = 1L)) } },
            { runBlocking { repo.deleteMemory(Memory("x", id = 1L)) } },
            { runBlocking { repo.deleteMessageById(1L) } },
            { runBlocking { repo.deleteMemoryById(1L) } },
        )
        var failures = 0
        for (attempt in attempts) {
            try { attempt() } catch (_: Throwable) { failures++ }
        }
        assertTrue(failures > 0, "SQLCipher queries should not succeed on the host JVM")
    }

    @Test fun settingsPageEditsPromptDeletesMemoryAndResets() {
        val vm = AssistantViewModel(
            app, OpenAssistantRepository.get(app),
            memoriesSource = flowOf(listOf(memory)),
            memoryDeleter = { deleted += it },
        )
        Shadows.shadowOf(app).clearStartedServices()
        val stack = NavBackStack(arrayOf<Route>(Route.ConversationPage(1L), Route.SettingsPage))
        rule.setContent { DynamicTheme { SettingsPage(stack, vm) } }
        rule.onNodeWithText("Settings").assertExists()
        rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("lives in Lisbon"))
        rule.onNodeWithText("lives in Lisbon").assertExists()
        // Edit the prompt, then reset restores the default text.
        rule.onNode(hasSetTextAction()).performTextReplacement("be terse")
        rule.onNodeWithText("be terse").assertExists()
        rule.onNodeWithText("Reset to default").performClick()
        // Delete the memory via its icon button.
        rule.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("Delete")), useUnmergedTree = true)
            .performClick()
        assertEquals(listOf(memory), deleted)
        assertEquals(2, stack.backStack.size)
    }
}
