package com.vayunmathur.office

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 screen tests (Robolectric): [DocumentScreen] end-to-end render
 * for text/sheet/presentation documents, [OfficeLightTheme] wrapping, and
 * the [OfficeRoute] navigation keys.
 */
@RunWith(AndroidJUnit4::class)
class Slice4ScreenTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun textDoc() = OdfDocument.TextDocument(
        "Notes.odt",
        listOf(
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("Title")), style = ParagraphStyle.HEADING1)),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("screen body")))),
        ),
    )

    private fun showDoc(document: OdfDocument, onBack: () -> Unit = {}) {
        rule.setContent {
            OfficeLightTheme {
                DocumentScreen(
                    document = document,
                    viewModel = vm(document),
                    activity = rule.activity,
                    onBack = onBack,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun textScreenRendersChromeAndBody() {
        showDoc(textDoc())
        // App-bar title, menu bar, and document body all render.
        rule.onNodeWithText("Notes.odt").assertIsDisplayed()
        rule.onNodeWithText("File").assertIsDisplayed()
        rule.onNodeWithText("screen body", substring = true).assertExists()
    }

    @Test fun presentationScreenRenders() {
        val doc = OdfDocument.Presentation(
            "Deck.odp",
            listOf(
                com.vayunmathur.library.ui.odf.OdfSlide(
                    "S1",
                    listOf(
                        com.vayunmathur.library.ui.odf.OdfSlideElement.Frame(
                            com.vayunmathur.library.ui.odf.OdfFrame(
                                0f, 0f, 100f, 50f,
                                listOf(OdfParagraph(listOf(OdfSpan("deck body")))))),
                    ),
                )),
        )
        showDoc(doc)
        rule.onNodeWithText("Deck.odp").assertIsDisplayed()
    }

    @Test fun routeKeys() {
        val off = OfficeRoute.OfflineEditor("content://x")
        assertEquals("content://x", off.uri)
        val on = OfficeRoute.OnlineEditor("doc1")
        assertEquals("doc1", on.docId)
        assertTrue(OfficeRoute.Main is OfficeRoute)
    }

    @Test fun lightThemeWrapsContent() {
        rule.setContent {
            OfficeLightTheme {
                androidx.compose.material3.Text("themed")
            }
        }
        rule.onNodeWithText("themed").assertIsDisplayed()
    }
}
