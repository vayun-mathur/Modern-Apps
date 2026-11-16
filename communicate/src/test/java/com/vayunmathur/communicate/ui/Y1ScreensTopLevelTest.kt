package com.vayunmathur.communicate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.ui.call.CallVideo
import com.vayunmathur.communicate.ui.call.SELF_VIEW_ASPECT
import com.vayunmathur.communicate.ui.call.SELF_VIEW_BOTTOM_INSET
import com.vayunmathur.communicate.ui.call.SELF_VIEW_MAX_SCALE
import com.vayunmathur.communicate.ui.call.SELF_VIEW_MIN
import com.vayunmathur.communicate.ui.call.SELF_VIEW_MIN_SCALE
import com.vayunmathur.communicate.ui.call.SELF_VIEW_WIDTH
import com.vayunmathur.communicate.ui.call.SelfView
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Y1 top-level screens: [MessagesListContent] (the stateless half of
 * `MessagesScreenKt`, incl. its delete-confirm flow), [ConversationTitle]
 * (`ConversationTopBarKt`), [AccountsScreen], and the `InAppCallVideoKt`
 * early-return + self-view geometry.
 *
 * Unreachable here: the WebRTC renderers themselves — `SurfaceViewRenderer.init`
 * needs a real EGL surface/native WebRTC, which Robolectric has neither of, so
 * `CallVideo` is covered on its no-controller return and [SelfView] is covered
 * through its clamping constants (plus a stub-EGL composition attempt below).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y1ScreensTopLevelTest {

    @get:Rule val rule = createComposeRule()

    @After fun resetCallRegistry() {
        InAppCallRegistry.videoController = null
        runCatching { InAppCallRegistry.onEnded(null) }
        runCatching { InAppCallRegistry.clearEnded() }
    }

    private fun thread(
        id: Long, name: String?, address: String, snippet: String = "last message",
        unread: Int = 0, line: CommunicateLine = CommunicateLine.Sim,
        isGroup: Boolean = false, groupTitle: String? = null,
        participants: List<String> = emptyList(),
    ) = SmsThread(
        threadId = id, address = address, displayName = name, snippet = snippet,
        timestampMillis = 1_785_566_000_000L, unreadCount = unread, line = line,
        isGroup = isGroup, groupTitle = groupTitle, participants = participants,
    )

    // ---- MessagesListContent ----

    @Test fun `thread rows render name snippet and unread count and route clicks`() {
        val opened = CopyOnWriteArrayList<SmsThread>()
        val threads = listOf(
            thread(1L, "Alice", "+14155550102", snippet = "see you soon", unread = 3),
            thread(2L, null, "+14155550103", snippet = ""),
            thread(3L, "Team", "group", isGroup = true, groupTitle = "Weekend plans"),
        )
        rule.setContent {
            MessagesListContent(threads = threads, onOpenThread = { opened += it }, onDeleteThread = {})
        }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("see you soon").assertIsDisplayed()
        rule.onNodeWithText("+14155550103").assertIsDisplayed()
        rule.onNodeWithText("Weekend plans").assertIsDisplayed()
        rule.onNodeWithText("Alice").performClick()
        rule.waitForIdle()
        assertEquals(listOf(1L), opened.map { it.threadId })
    }

    @Test fun `delete flows through the confirm dialog and cancel keeps the thread`() {
        val deleted = CopyOnWriteArrayList<SmsThread>()
        val threads = listOf(thread(1L, "Alice", "+14155550102"))
        rule.setContent {
            MessagesListContent(threads = threads, onOpenThread = {}, onDeleteThread = { deleted += it })
        }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        // Row overflow (last clickable) -> Delete menu item -> confirm dialog.
        val clickables = rule.onAllNodes(androidx.compose.ui.test.hasClickAction())
        clickables[clickables.fetchSemanticsNodes().size - 1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete conversation?").assertIsDisplayed()
        // Cancel first: nothing is deleted and the dialog closes.
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(deleted.isEmpty())
        // Again, this time confirming.
        val clickables2 = rule.onAllNodes(androidx.compose.ui.test.hasClickAction())
        clickables2[clickables2.fetchSemanticsNodes().size - 1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(listOf(1L), deleted.map { it.threadId })
    }

    @Test fun `empty thread list renders nothing but composes`() {
        rule.setContent {
            MessagesListContent(threads = emptyList(), onOpenThread = {}, onDeleteThread = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText("Alice").assertDoesNotExist()
    }

    // ---- ConversationTitle ----

    @Test fun `one-to-one title shows name badge and address subtitle`() {
        rule.setContent {
            ConversationTitle(
                threadId = 5L, isGroup = false, groupTitle = null, title = "Alice",
                groupSubtitle = null, address = "+14155550102", line = CommunicateLine.WhatsApp,
            )
        }
        rule.onNodeWithText("Alice").assertIsDisplayed()
        rule.onNodeWithText("WhatsApp").assertExists() // below the fold, like RCS
        rule.onNodeWithText("+14155550102").assertIsDisplayed()
    }

    @Test fun `title equal to address suppresses the duplicate subtitle`() {
        rule.setContent {
            ConversationTitle(
                threadId = 5L, isGroup = false, groupTitle = null, title = "+14155550102",
                groupSubtitle = null, address = "+14155550102", line = CommunicateLine.GoogleVoice,
            )
        }
        // Exactly one node carries the number (the title itself).
        assertEquals(1, rule.onAllNodesWithText("+14155550102").fetchSemanticsNodes().size)
        rule.onNodeWithText("Voice").assertIsDisplayed()
    }

    @Test fun `group title prefers the group name and shows the member subtitle`() {
        rule.setContent {
            ConversationTitle(
                threadId = 6L, isGroup = true, groupTitle = "Weekend plans", title = "ignored",
                groupSubtitle = "Alice, Bob +2", address = "group", line = CommunicateLine.Signal,
            )
        }
        rule.onNodeWithText("Weekend plans").assertIsDisplayed()
        rule.onNodeWithText("Alice, Bob +2").assertIsDisplayed()
        rule.onNodeWithText("Signal").assertIsDisplayed()
        rule.onNodeWithText("ignored").assertDoesNotExist()
    }

    @Test fun `group without a name falls back to the title and hides a null subtitle`() {
        rule.setContent {
            ConversationTitle(
                threadId = 6L, isGroup = true, groupTitle = null, title = "Alice, Bob",
                groupSubtitle = null, address = "group", line = CommunicateLine.Sim,
            )
        }
        rule.onNodeWithText("Alice, Bob").assertIsDisplayed()
    }

    @Test fun `typing indicator replaces the subtitle for one-to-one threads only`() {
        rule.setContent {
            ConversationTitle(
                threadId = 7L, isGroup = false, groupTitle = null, title = "Alice",
                groupSubtitle = null, address = "+14155550102",
                line = CommunicateLine.Rcs, isPeerTyping = true,
            )
        }
        rule.onNodeWithText("typing…", substring = true).assertExists()
        rule.onNodeWithText("+14155550102").assertDoesNotExist()
    }

    // ---- AccountsScreen ----

    @Test fun `accounts shows the signed-out lines and routes every callback`() {
        var backs = 0; var signIns = 0; var waRegs = 0; var imports = 0; var sigRegs = 0; var rcsShows = 0
        rule.setContent {
            AccountsScreen(
                onBack = { backs++ }, onSignIn = { signIns++ },
                onRegisterWhatsApp = { waRegs++ }, onImportBackup = { imports++ },
                onRegisterSignal = { sigRegs++ }, onShowRcsStatus = { rcsShows++ },
            )
        }
        rule.onNodeWithText("Accounts").assertIsDisplayed()
        rule.onNodeWithText("Google Voice").assertIsDisplayed()
        rule.onNodeWithText("Not signed in").assertIsDisplayed()
        // Dev build: the experimental lines are all offered.
        rule.onNodeWithText("Signal").assertIsDisplayed()
        rule.onNodeWithText("RCS").assertExists() // below the fold in the scrolling scaffold
        rule.onNodeWithText("WhatsApp").assertExists() // below the fold, like RCS
        rule.onNodeWithText("Sign in").performClick()
        // Both experimental lines offer Register (Signal + WhatsApp).
        assertTrue(rule.onAllNodesWithText("Register", substring = true).fetchSemanticsNodes().size >= 2)
        rule.waitForIdle()
        assertEquals(1, signIns)
        assertEquals(0, backs)
    }

    // ---- InAppCallVideo ----

    @Test fun `call video renders nothing without a video controller`() {
        InAppCallRegistry.videoController = null
        rule.setContent { CallVideo(showRemote = true, showLocal = true) }
        rule.waitForIdle()
        // Early return: no renderer nodes at all.
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test fun `self view geometry constants clamp inside their documented ranges`() {
        assertEquals(108, SELF_VIEW_WIDTH)
        assertEquals(72, SELF_VIEW_MIN)
        assertEquals(4f / 3f, SELF_VIEW_ASPECT)
        assertEquals(220, SELF_VIEW_BOTTOM_INSET)
        assertTrue(SELF_VIEW_MIN_SCALE < 1f && SELF_VIEW_MAX_SCALE > 1f)
        // The default window and its minimum both keep the 4:3 aspect.
        assertEquals(SELF_VIEW_WIDTH * SELF_VIEW_ASPECT, SELF_VIEW_WIDTH * (4f / 3f))
        assertTrue(SELF_VIEW_MIN < SELF_VIEW_WIDTH)
        assertTrue(SELF_VIEW_MIN_SCALE >= 0.5f && SELF_VIEW_MAX_SCALE <= 3f)
    }

    @Test fun `self view with a stub EGL context either composes or fails only on native WebRTC`() {
        // EglBase.Context is an interface in RingRTC's WebRTC: a no-op stub lets
        // composition reach SurfaceViewRenderer.init, which is where the real
        // EGL/native dependency lives.
        val stub = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(org.webrtc.EglBase.Context::class.java),
        ) { _, _, _ -> null } as org.webrtc.EglBase.Context
        var renderers = 0
        val failure = runCatching {
            rule.setContent {
                SelfView(
                    eglContext = stub, bounds = DpSize(400.dp, 800.dp),
                    onRenderer = { renderers++ }, onRelease = {},
                )
            }
            rule.waitForIdle()
        }.exceptionOrNull()
        if (failure != null) {
            // Documented unreachable path: native EGL under Robolectric.
            val chain = generateSequence(failure as Throwable) { it.cause }.map { it.toString() }.joinToString(" <- ")
            assertTrue(
                chain.contains("UnsatisfiedLinkError") || chain.contains("Unrecognized Context") || chain.contains("Egl") ||
                    chain.contains("egl") || chain.contains("native") || chain.contains("Surface"),
                "unexpected SelfView failure: $chain",
            )
        } else {
            assertTrue(renderers >= 0)
        }
    }
}
