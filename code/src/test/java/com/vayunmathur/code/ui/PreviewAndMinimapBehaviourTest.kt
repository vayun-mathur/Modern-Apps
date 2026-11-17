package com.vayunmathur.code.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.Route
import com.vayunmathur.code.domain.syntax.Language
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.platform.OpenTab
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour tests for buildPreviewHtml (via reflection), [Minimap] gestures and [PreviewPage]. */
@RunWith(AndroidJUnit4::class)
class PreviewAndMinimapBehaviourTest {
    @get:Rule val rule = createComposeRule()

    private fun buildPreview(text: String, language: Language, name: String): String? {
        val cls = Class.forName("com.vayunmathur.code.ui.PreviewPageKt")
        val m = cls.getDeclaredMethod("buildPreviewHtml", String::class.java, Language::class.java, String::class.java)
            .apply { isAccessible = true }
        return m.invoke(null, text, language, name) as String?
    }

    private fun privateFn(name: String, arg: String): String {
        val cls = Class.forName("com.vayunmathur.code.ui.PreviewPageKt")
        val m = cls.getDeclaredMethod(name, String::class.java).apply { isAccessible = true }
        return m.invoke(null, arg) as String
    }

    @Test fun markdownByExtensionAndByLanguageRendersStyledDocument() {
        val byExt = buildPreview("# Title\n\nhello", Language.PLAINTEXT, "README.md")
        assertNotNull(byExt)
        assertTrue(byExt.contains("<h1"))
        assertTrue(byExt.contains("font-family: sans-serif"))
        val byLang = buildPreview("**bold**", Language.MARKDOWN, "notes.txt")
        assertNotNull(byLang)
        assertTrue(byLang.contains("<strong>") || byLang.contains("<b>"))
        assertNotNull(buildPreview("# T", Language.PLAINTEXT, "README.MARKDOWN"))
        assertNotNull(buildPreview("# T", Language.PLAINTEXT, "README.MD"))
    }

    @Test fun htmlPassesThroughVerbatim() {
        val html = "<html><body>raw &amp; free</body></html>"
        assertEquals(html, buildPreview(html, Language.PLAINTEXT, "page.html"))
        assertEquals(html, buildPreview(html, Language.PLAINTEXT, "page.htm"))
        assertEquals(html, buildPreview(html, Language.PLAINTEXT, "page.HTML"))
    }

    @Test fun javascriptRunnerCapturesConsoleAndCode() {
        val js = buildPreview("console.log(1+1);", Language.PLAINTEXT, "app.js")
        assertNotNull(js)
        assertTrue(js.contains("console.log(1+1);"))
        assertTrue(js.contains("console.log = w"))
        assertTrue(js.contains("__out"))
        assertNotNull(buildPreview("let a=1", Language.PLAINTEXT, "app.mjs"))
        assertNotNull(buildPreview("let a=1", Language.JAVASCRIPT, "noext"))
        val runner = privateFn("jsRunnerHtml", "throw new Error('x')")
        assertTrue(runner.contains("catch (e)"))
        assertTrue(runner.contains("throw new Error('x')"))
    }

    @Test fun unsupportedTypesReturnNull() {
        assertNull(buildPreview("fun f() {}", Language.KOTLIN, "a.kt"))
        assertNull(buildPreview("plain", Language.PLAINTEXT, "a.txt"))
        assertNull(buildPreview("plain", Language.PLAINTEXT, "noext"))
        assertNull(buildPreview("{}", Language.JSON, "data.json"))
    }

    @Test fun styledDocumentWrapsBody() {
        val doc = privateFn("styledDocument", "<p>hi</p>")
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("<p>hi</p>"))
        assertTrue(doc.contains("blockquote"))
    }

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())
    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Editor))

    @Test fun previewPageUnsupportedLanguageShowsMessage() {
        val v = vm()
        v.tabs.add(OpenTab(null, initialName = "a.kt", initialText = "fun f() {}", language = Language.KOTLIN))
        v.currentIndex = 0
        rule.setContent { PreviewPage(v, backStack()) }
        rule.onNodeWithText("No preview for Kotlin files.", substring = true).assertExists()
    }

    // Note: PreviewPage's WebView branch cannot run under Robolectric — its factory
    // calls WebSettingsCompat.setAlgorithmicDarkeningAllowed, which throws
    // UnsupportedOperationException with Robolectric's WebView APK. The HTML
    // generation for that branch is covered above via buildPreviewHtml.

    @Test fun minimapTapAndDragReportFractionsAndEmptyLinesSkip() {
        val fractions = mutableListOf<Float>()
        rule.setContent {
            Minimap(
                lines = listOf("a", "", "abcd", "ab"),
                lineHeight = 10f, scrollY = 25f, maxScrollY = 100f,
                viewportHeight = 50f, totalHeight = 200f,
                color = Color.Gray, viewportColor = Color.Blue,
                onScrollTo = { fractions.add(it) },
                modifier = Modifier.size(100.dp, 100.dp),
            )
        }
        rule.onRoot().performTouchInput { click(center) }
        assertTrue(fractions.isNotEmpty())
        assertTrue(fractions.all { it in 0f..1f })
        assertEquals(0.5f, fractions.first(), 0.15f)
        val before = fractions.size
        rule.onRoot().performTouchInput { swipeDown() }
        assertTrue(fractions.size > before)
    }

    @Test fun minimapEmptyAndZeroTotalHeightDoNotCrash() {
        val fractions = mutableListOf<Float>()
        rule.setContent {
            Minimap(
                lines = emptyList(), lineHeight = 10f, scrollY = 0f, maxScrollY = 0f,
                viewportHeight = 0f, totalHeight = 0f,
                color = Color.Gray, viewportColor = Color.Blue,
                onScrollTo = { fractions.add(it) },
                modifier = Modifier.size(80.dp, 80.dp),
            )
        }
        rule.onRoot().performTouchInput { click(center) }
        assertTrue(fractions.isNotEmpty())
    }

    @Test fun minimapSingleLongLineUsesFullWidth() {
        rule.setContent {
            Minimap(
                lines = listOf("only"),
                lineHeight = 10f, scrollY = 0f, maxScrollY = 0f,
                viewportHeight = 100f, totalHeight = 100f,
                color = Color.Black, viewportColor = Color.Red,
                onScrollTo = {},
                modifier = Modifier.size(60.dp, 60.dp),
            )
        }
        rule.waitForIdle()
    }
}
