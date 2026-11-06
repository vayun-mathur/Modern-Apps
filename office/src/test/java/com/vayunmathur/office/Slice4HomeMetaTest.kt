package com.vayunmathur.office

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfMetadata
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4/4 home + metadata tests (Robolectric + createComposeRule):
 * [HomeScreen]/[InitialScreen] (VM-free lambdas except InitialScreen's
 * create-new wiring) and [MetadataDialog] save semantics.
 */
@RunWith(AndroidJUnit4::class)
class Slice4HomeMetaTest {

    @get:Rule val rule = createComposeRule()

    // ---- HomeScreen ----

    @Test fun homeScreenShowsActions() {
        var opened = false
        var text = false
        var sheet = false
        var pres = false
        rule.setContent {
            HomeScreen(
                onOpenDocument = { opened = true },
                onNewTextDocument = { text = true },
                onNewSpreadsheet = { sheet = true },
                onNewPresentation = { pres = true },
            )
        }
        rule.onNodeWithText("Open Document").assertIsDisplayed()
        rule.onNodeWithText("New Doc").assertIsDisplayed()
        rule.onNodeWithText("New Sheet").assertIsDisplayed()
        rule.onNodeWithText("New Slides").assertIsDisplayed()
        assertTrue(!opened && !text && !sheet && !pres)
    }

    @Test fun homeScreenNewDocumentCallbacks() {
        var text = false
        var sheet = false
        var pres = false
        rule.setContent {
            HomeScreen(
                onNewTextDocument = { text = true },
                onNewSpreadsheet = { sheet = true },
                onNewPresentation = { pres = true },
            )
        }
        rule.onNodeWithText("New Sheet").performClick()
        assertTrue(sheet)
        rule.onNodeWithText("New Slides").performClick()
        assertTrue(pres)
        assertTrue(!text)
    }

    // ---- MetadataDialog ----

    @Test fun metadataDialogShowsValuesAndStats() {
        rule.setContent {
            MetadataDialog(
                metadata = OdfMetadata(
                    title = "Report",
                    author = "me",
                    pageCount = 3,
                    wordCount = 100,
                    fileSize = 2048,
                ),
                onSave = {},
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Report").assertIsDisplayed()
        rule.onNodeWithText("me").assertIsDisplayed()
        // Stat rows sit below the fold in the scrollable dialog: existence only.
        rule.onNodeWithText("3").assertExists()
        rule.onNodeWithText("100", substring = true).assertExists()
    }

    @Test fun metadataDialogSaveAppliesEdits() {
        var saved: OdfMetadata? = null
        var dismissed = false
        rule.setContent {
            MetadataDialog(
                metadata = OdfMetadata(title = "Old", keywords = listOf("a")),
                onSave = { saved = it },
                onDismiss = { dismissed = true },
            )
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        // Title field first: clear + retype.
        fields[0].performTextClearance()
        fields[0].performTextInput("New title")
        // Keywords field last: add a second keyword.
        fields[4].performTextClearance()
        fields[4].performTextInput("a, b")
        rule.onNodeWithText("Save").performClick()
        val m = saved
        assertTrue(m != null)
        assertEquals("New title", m!!.title)
        assertEquals(listOf("a", "b"), m.keywords)
        assertTrue(dismissed)
    }

    @Test fun metadataDialogBlankTitleBecomesNull() {
        var saved: OdfMetadata? = null
        rule.setContent {
            MetadataDialog(
                metadata = OdfMetadata(title = "Old", author = "me"),
                onSave = { saved = it },
                onDismiss = {},
            )
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextClearance()
        fields[1].performTextClearance()
        rule.onNodeWithText("Save").performClick()
        assertNull(saved?.title)
        assertNull(saved?.author)
        // Untouched description stays null; keywords empty.
        assertTrue(saved?.keywords?.isEmpty() == true)
    }

    @Test fun metadataDialogCancelDismissesWithoutSave() {
        var saved: OdfMetadata? = null
        var dismissed = false
        rule.setContent {
            MetadataDialog(
                metadata = OdfMetadata(),
                onSave = { saved = it },
                onDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("Cancel").performClick()
        assertNull(saved)
        assertTrue(dismissed)
    }

    @Test fun formatFileSizeBoundaries() {
        assertEquals("1023 B", formatFileSize(1023))
        assertEquals("1 KB", formatFileSize(1024))
        assertEquals("1023 KB", formatFileSize(1023 * 1024))
        assertEquals("1.0 MB", formatFileSize(1024 * 1024))
    }
}
