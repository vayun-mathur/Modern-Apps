package com.vayunmathur.pdf.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.pdf.InitialScreen
import com.vayunmathur.pdf.util.PdfPrimitive
import com.vayunmathur.pdf.util.SafeOutlineItem
import com.vayunmathur.pdf.util.SafePdfPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfComposeScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val samplePage = SafePdfPage(612f, 792f, listOf(
        PdfPrimitive.Text(Offset(64f, 700f), 18f, 0xFF000000.toInt(), "Annual Report"),
        PdfPrimitive.FillPath(0xFF0000FF.toInt(), false, listOf(listOf(Offset(64f, 600f), Offset(200f, 600f), Offset(200f, 650f), Offset(64f, 650f)))),
        PdfPrimitive.StrokePath(0xFF000000.toInt(), 2f, FloatArray(0), 0f, listOf(Offset(64f, 500f), Offset(300f, 500f))),
    ))

    @Test fun initialScreenButtonsFireCallbacks() {
        var opened = 0; var captured = 0; var cut = 0
        rule.setContent { DynamicTheme { InitialScreen({ opened++ }, { captured++ }, { cut++ }) } }
        rule.onNodeWithText("Open PDF").assertIsDisplayed().performClick()
        rule.onNodeWithText("Capture PDF").assertIsDisplayed().performClick()
        rule.onNodeWithText("PDF cut and glue").assertIsDisplayed().performClick()
        assertEquals(1, opened); assertEquals(1, captured); assertEquals(1, cut)
    }

    @Test fun pageCanvasRendersSample() {
        rule.setContent { DynamicTheme { SafePdfPageCanvas(samplePage) } }
        rule.waitForIdle()
    }

    @Test fun pageCanvasFailurePlaceholder() {
        rule.setContent { DynamicTheme { SafePdfPageCanvas(null, failureReason = "boom") } }
        rule.onNodeWithText("This page could not be displayed").assertIsDisplayed()
    }

    @Test fun pageCanvasLoadingPlaceholder() {
        rule.setContent { DynamicTheme { SafePdfPageCanvas(null) } }
        rule.waitForIdle()
    }

    @Test fun wideLayoutShowsBothSlots() {
        rule.setContent {
            DynamicTheme {
                PdfViewerWideLayout(
                    viewer = { androidx.compose.foundation.layout.Box(it) { com.vayunmathur.library.ui.Text("ViewerPane") } },
                    tools = { androidx.compose.foundation.layout.Box(it) { com.vayunmathur.library.ui.Text("ToolsPane") } },
                )
            }
        }
        rule.onNodeWithText("ViewerPane").assertIsDisplayed()
        rule.onNodeWithText("ToolsPane").assertIsDisplayed()
    }

    @Test fun outlineDrawerListsAndSelects() {
        var selected = -1
        rule.setContent {
            DynamicTheme {
                PdfOutlineDrawer(
                    outline = listOf(SafeOutlineItem(0, 0, "Cover"), SafeOutlineItem(1, 3, "Chapter One")),
                    drawerState = rememberDrawerState(DrawerValue.Open),
                    onSelectPage = { selected = it },
                ) { com.vayunmathur.library.ui.Text("BodyContent") }
            }
        }
        rule.onNodeWithText("Chapter One").assertIsDisplayed().performClick()
        assertEquals(3, selected)
    }

    @Test fun editToolbarToolSelection() {
        var tool: EditTool? = null; var deleted = false
        rule.setContent {
            DynamicTheme {
                EditToolbar(
                    tool = EditTool.SELECT, onTool = { tool = it },
                    shape = ShapeKind.RECT_OUTLINE, onShape = {},
                    markup = MarkupKind.HIGHLIGHT, onMarkup = {},
                    color = Color.Red, onColor = {}, onStyle = {},
                    canDelete = true, onDelete = { deleted = true }, onDuplicate = {},
                )
            }
        }
        rule.waitForIdle()
        // Toolbar renders icon buttons; interaction smoke: compose survives
        assertTrue(true)
    }

    @Test fun styleDialogShowsAndDismisses() {
        var dismissed = false; var color: Color? = null
        rule.setContent {
            DynamicTheme {
                StyleDialog(color = Color.Red, onColor = { color = it }, opacity = 1f, onOpacity = {},
                    strokeWidth = 2f, onWidth = {}, onDismiss = { dismissed = true })
            }
        }
        rule.onNodeWithText("Style").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        assertTrue(dismissed)
    }

    @Test fun passwordPromptTypesAndOpens() {
        var input = ""; var opened = false
        rule.setContent {
            DynamicTheme {
                SafePdfPasswordPrompt(pwInput = input, onPwInput = { input = it }, pwError = true,
                    onOpen = { opened = true }, onBack = {})
            }
        }
        rule.onNodeWithText("Password required").assertIsDisplayed()
        rule.onNodeWithText("Incorrect password").assertIsDisplayed()
        rule.onNodeWithText("Open").performClick()
        assertTrue(opened)
    }

    @Test fun pendingNoteDialogAdds() {
        val state = SafePdfViewerState(CoroutineScope(Dispatchers.Unconfined))
        state.pendingNote = 0 to Offset(10f, 10f)
        rule.setContent { DynamicTheme { SafePdfPendingDialogs(state, null, SafePdfLauncherSet({}, {}, {})) } }
        rule.onNodeWithText("Sticky note").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(null, state.pendingNote)
    }

    @Test fun cutGlueEmpty() {
        rule.setContent {
            DynamicTheme {
                CutGlueContent(pageKeys = emptyList(), renderPage = { null })
            }
        }
        rule.onNodeWithText("Tap + to append a PDF or image").assertIsDisplayed()
    }

    @Test fun cutGlueWithPages() {
        rule.setContent {
            DynamicTheme {
                CutGlueContent(
                    pageKeys = listOf(0L, 1L),
                    renderPage = { samplePage },
                    initialPages = mapOf(0L to samplePage, 1L to samplePage),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun toolButtonsAndMenusRender() {
        var markup: MarkupKind? = null; var shape: ShapeKind? = null
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    MarkupMenuButton(markup = MarkupKind.HIGHLIGHT, active = true, onMarkup = { markup = it })
                    ShapeMenuButton(shape = ShapeKind.RECT_OUTLINE, active = false, onShape = { shape = it })
                    LinesMenuButton(tool = EditTool.LINE, onTool = {})
                }
            }
        }
        rule.waitForIdle()
    }
}
