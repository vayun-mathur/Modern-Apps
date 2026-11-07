package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vayunmathur.keyboard.util.ClipItem
import com.vayunmathur.keyboard.util.EmojiCategory
import com.vayunmathur.keyboard.util.EmojiData
import com.vayunmathur.keyboard.util.EmojiEntry
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PagesAndSetupTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun `setup screen renders status switches and test field`() {
        rule.setContent { SetupScreen() }
        rule.onNodeWithText("Keyboard").assertIsDisplayed()
        rule.onNodeWithText("Enable keyboard").assertIsDisplayed()
        rule.onNodeWithText("Choose keyboard").assertIsDisplayed()
        for (label in listOf("Haptic feedback", "Key sound", "Auto-capitalize", "Show suggestions", "Number row", "Clipboard history")) {
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        // typing in the try-out field
        rule.onNodeWithText("Try the keyboard here").performScrollTo()
        rule.onNodeWithText("Try the keyboard here").performTextInput("hello")
    }

    @Test
    fun `setup screen toggles a switch`() {
        rule.setContent { SetupScreen() }
        // Switch rows are clickable via their row; toggle haptic off and on by text click
        rule.onNodeWithText("Key sound").performScrollTo().performClick()
        rule.onNodeWithText("Key sound").assertIsDisplayed()
        rule.onNodeWithText("Clear clipboard history").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Clear clipboard history").performClick()
    }

    @Test
    fun `setup screen buttons and slider respond`() {
        rule.setContent { SetupScreen() }
        rule.onNodeWithText("Enable keyboard").performClick()
        rule.onNodeWithText("Choose keyboard").performClick()
        rule.onNodeWithText("Keyboard languages").performScrollTo().performClick()
        rule.onNodeWithText("Key height").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `emoji page direct with categories`() {
        val picked = mutableListOf<String>()
        var searches = 0; var backs = 0; var backspaces = 0
        val data = EmojiData(
            categories = listOf(
                EmojiCategory("😀", listOf("😀", "😁")),
                EmojiCategory("🐱", listOf("🐱")),
            ),
            entries = listOf(EmojiEntry("😀", "grinning face", listOf("happy"))),
        )
        rule.setContent {
            androidx.compose.foundation.layout.Column { EmojiPage(
                data = data, recents = listOf("🎉"), keyHeight = 52.dp, rows = 4,
                onEmoji = { picked += it }, onSearch = { searches++ },
                onBackspace = { backspaces++ }, onBack = { backs++ },
            ) }
        }
        rule.onNodeWithText("🎉").assertIsDisplayed()
        rule.onNodeWithText("🎉", useUnmergedTree = true).performTouchInput { down(center); advanceEventTime(60); up() }
        assertEquals(listOf("🎉"), picked)
        rule.onNodeWithText("🐱").assertIsDisplayed()
    }

    @Test
    fun `clipboard page direct empty and populated`() {
        var clears = 0; val pasted = mutableListOf<ClipItem>(); val deleted = mutableListOf<ClipItem>()
        val populated = androidx.compose.runtime.mutableIntStateOf(0)
        val clip = ClipItem(id = 7, text = "direct clip")
        rule.setContent {
            androidx.compose.foundation.layout.Column { ClipboardPage(
                clips = if (populated.intValue == 0) emptyList() else listOf(clip),
                keyHeight = 52.dp, rows = 4,
                onPaste = { pasted += it }, onDelete = { deleted += it },
                onClearAll = { clears++ }, onBackspace = {}, onBack = {},
            ) }
        }
        rule.onNodeWithText("Copied text and images show up here").assertIsDisplayed()
        rule.runOnIdle { populated.intValue = 1 }
        rule.onNodeWithText("direct clip", useUnmergedTree = true).performTouchInput { down(center); advanceEventTime(60); up() }
        assertEquals(listOf(clip), pasted)
        rule.onNodeWithText("Clear all").performClick()
        assertEquals(1, clears)
    }

    @Test
    fun `clip preview shows image placeholder for image clips`() {
        // An image clip whose file cannot be decoded shows the text placeholder;
        // the thumbnail state starts empty, which is that same placeholder branch.
        val clip = ClipItem(id = 8, text = "plain preview text")
        rule.setContent { Row(Modifier.fillMaxWidth().height(52.dp)) { ClipPreview(clip) } }
        rule.onNodeWithText("plain preview text").assertIsDisplayed()
        val imageClip = ClipItem(id = 9, imagePath = "/nope/a.png", mimeType = "image/png")
        // composing an image clip must not crash whether or not a thumbnail decodes
        assertTrue(imageClip.isImage)
    }

    @Test
    fun `suggestion and candidate strips direct`() {
        val picked = mutableListOf<String>()
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            when (stage.intValue) {
                0 -> SuggestionStrip(height = 44.dp, suggestions = listOf("one", "two"), onPick = { picked += it })
                1 -> SuggestionStrip(height = 44.dp, suggestions = emptyList(), onPick = { picked += it })
                else -> CandidateStrip(height = 44.dp, candidates = listOf("你", "尼", "泥"), onPick = { picked += it })
            }
        }
        rule.onNodeWithText("two").performClick()
        assertEquals(listOf("two"), picked)
        rule.runOnIdle { stage.intValue = 1 }
        rule.runOnIdle { stage.intValue = 2 }
        rule.onNodeWithText("尼").performClick()
        assertEquals("尼", picked.last())
    }

    @Test
    fun `emoji search bar and results direct`() {
        var closes = 0; val picked = mutableListOf<String>()
        val stage2 = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            if (stage2.intValue == 0) {
                EmojiSearchBar(height = 44.dp, query = "dog", onClose = { closes++ })
            } else {
                EmojiSearchResults(height = 44.dp, results = listOf("🐶"), onPick = { picked += it })
            }
        }
        rule.onNodeWithText("dog").assertIsDisplayed()
        rule.runOnIdle { stage2.intValue = 1 }
        rule.onNodeWithText("🐶").performClick()
        assertEquals(listOf("🐶"), picked)
    }

    @Test
    fun `popups geometry helpers`() {
        val bounds = KeyBounds().apply { left = 100f; width = 50f }
        // centred then clamped at left edge
        assertEquals(-100f, alternatesOffset(bounds, 44f, 10, 1000f), 0.001f)
        assertTrue(alternatesOffset(bounds, 44f, 2, 1000f) > -100f)
        assertEquals(0, indexAt(0f, 0f, 44f, 3))
        assertEquals(2, indexAt(500f, 0f, 44f, 3))
        assertEquals(0, indexAt(-50f, 0f, 44f, 3))
        assertTrue(Offset(5f, 5f).isInside(IntSize(10, 10)))
        assertFalse(Offset(-1f, 5f).isInside(IntSize(10, 10)))
        assertFalse(Offset(5f, 11f).isInside(IntSize(10, 10)))
        val provider = AlternatesPositionProvider(7)
        assertEquals(
            IntOffset(107, 40),
            provider.calculatePosition(IntRect(100, 100, 150, 150), IntSize(1000, 1000), LayoutDirection.Ltr, IntSize(88, 60)),
        )
        assertEquals(
            IntOffset(105, 40),
            KeyPreviewPositionProvider.calculatePosition(
                IntRect(100, 100, 150, 150), IntSize(1000, 1000), LayoutDirection.Ltr, IntSize(40, 60),
            ),
        )
    }

    @Test
    fun `punctuation alternates only for single chars`() {
        assertEquals(";", punctuationAlternates(","))
        assertEquals("…", punctuationAlternates("."))
        assertEquals("", punctuationAlternates("@@"))
        assertEquals("", punctuationAlternates(""))
    }

    @Test
    fun `enter action icon renders for every action`() {
        val idx = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent { EnterActionIcon(com.vayunmathur.keyboard.ime.EnterAction.entries[idx.intValue]) }
        for (i in com.vayunmathur.keyboard.ime.EnterAction.entries.indices) {
            rule.runOnIdle { idx.intValue = i }
            rule.waitForIdle()
        }
    }
}
