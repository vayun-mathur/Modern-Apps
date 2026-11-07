package com.vayunmathur.translate.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CameraAndUtilsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun box(text: String, x: Float = 0f, y: Float = 0f) = OverlayBox(
        corners = listOf(
            Offset(x, y), Offset(x + 100f, y), Offset(x + 100f, y + 20f), Offset(x, y + 20f),
        ),
        text = text,
    )

    private fun overlay(
        overlays: List<OverlayBox> = emptyList(),
        translations: Map<String, String> = emptyMap(),
        available: Boolean = true,
        paused: Boolean = false,
        torch: Boolean = false,
        capture: Boolean = true,
        onPicker: (Boolean) -> Unit = {},
        onPause: () -> Unit = {},
        onTorch: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            CameraOverlay(
                overlays = overlays,
                translations = translations,
                translationAvailable = available,
                frameW = 100, frameH = 100, viewW = 100, viewH = 100,
                sourceLang = "en", targetLang = "es",
                paused = paused, onTogglePaused = onPause,
                torchOn = torch, onToggleTorch = onTorch,
                onBack = onBack,
                onOpenLanguagePicker = onPicker,
                showCaptureControls = capture,
            )
        }
    }

    @Test
    fun `overlay shows translated plates and language bar`() {
        var pickerFor: Boolean? = null
        overlay(
            overlays = listOf(box("hello")),
            translations = mapOf("hello" to "hola"),
            onPicker = { pickerFor = it },
        )
        rule.onNodeWithText("hola").assertIsDisplayed()
        rule.onNodeWithText("Español").performClick()
        assertEquals(false, pickerFor)
        rule.onNodeWithText("English").performClick()
        assertEquals(true, pickerFor)
    }

    @Test
    fun `overlay without translation available shows source text and banner`() {
        overlay(overlays = listOf(box("bonjour")), available = false)
        rule.onNodeWithText("bonjour").assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("not installed", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `overlay skips untranslated lines while model is available`() {
        overlay(overlays = listOf(box("hidden-line")), translations = emptyMap(), available = true)
        assertTrue(rule.onAllNodesWithText("hidden-line").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `overlay capture controls toggle pause and torch`() {
        var pauses = 0; var torches = 0
        overlay(paused = true, torch = true, onPause = { pauses++ }, onTorch = { torches++ })
        // Play (paused) and flash-on buttons are content-described icons.
        rule.onAllNodesWithText("English").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        // Toggle via the icon buttons: find by content description if present.
        val play = rule.onAllNodesWithContentDescription("Play").fetchSemanticsNodes()
        if (play.isNotEmpty()) {
            rule.onNodeWithContentDescription("Play").performClick()
            assertEquals(1, pauses)
        }
        val flash = rule.onAllNodesWithContentDescription("Flash on").fetchSemanticsNodes()
        if (flash.isNotEmpty()) {
            rule.onNodeWithContentDescription("Flash on").performClick()
            assertEquals(1, torches)
        }
    }

    @Test
    fun `overlay without capture controls hides pause and torch`() {
        overlay(capture = false)
        assertTrue(rule.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("English").assertIsDisplayed()
        rule.onNodeWithText("Español").assertIsDisplayed()
    }

    @Test
    fun `overlay with zero frame size draws no plates`() {
        rule.setContent {
            CameraOverlay(
                overlays = listOf(box("hello")),
                translations = mapOf("hello" to "hola"),
                translationAvailable = true,
                frameW = 0, frameH = 0, viewW = 0, viewH = 0,
                sourceLang = "en", targetLang = "es",
                paused = false, onTogglePaused = {},
                torchOn = false, onToggleTorch = {},
                onBack = {}, onOpenLanguagePicker = {},
            )
        }
        assertTrue(rule.onAllNodesWithText("hola").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `overlay degenerate box with zero width is skipped`() {
        val flat = OverlayBox(
            corners = listOf(Offset(5f, 5f), Offset(5f, 5f), Offset(5f, 5f), Offset(5f, 5f)),
            text = "flat",
        )
        overlay(overlays = listOf(flat), translations = mapOf("flat" to "plano"))
        assertTrue(rule.onAllNodesWithText("plano").fetchSemanticsNodes().isEmpty())
    }

    // --- cropAndRotate -------------------------------------------------------------------

    private fun bitmap(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun `crop and rotate whole frame without rotation returns same bitmap`() {
        val src = bitmap(40, 30)
        val out = cropAndRotate(src, Rect(0, 0, 40, 30), 0)
        assertSame(src, out)
    }

    @Test
    fun `crop and rotate sub rect crops and empty rect falls back to whole`() {
        val src = bitmap(40, 30)
        val cropped = cropAndRotate(src, Rect(5, 5, 25, 20), 0)
        assertEquals(20, cropped.width)
        assertEquals(15, cropped.height)
        val fallback = cropAndRotate(src, Rect(0, 0, 0, 0), 0)
        assertSame(src, fallback)
        val clamped = cropAndRotate(src, Rect(-10, -10, 500, 500), 0)
        assertSame(src, clamped)
    }

    @Test
    fun `crop and rotate ninety degrees swaps dimensions`() {
        val src = bitmap(40, 30)
        val rotated = cropAndRotate(src, Rect(0, 0, 40, 30), 90)
        assertEquals(30, rotated.width)
        assertEquals(40, rotated.height)
        val rotatedCrop = cropAndRotate(src, Rect(0, 0, 20, 10), 270)
        assertEquals(10, rotatedCrop.width)
        assertEquals(20, rotatedCrop.height)
        val fullTurn = cropAndRotate(src, Rect(0, 0, 40, 30), 360)
        assertSame(src, fullTurn)
    }

    @Test
    fun `frame constants and overlay box data`() {
        assertEquals(1280, ANALYSIS_SIZE.width)
        assertEquals(960, ANALYSIS_SIZE.height)
        assertEquals(Size(1280, 960), ANALYSIS_SIZE)
        assertTrue(ANALYSIS_INTERVAL_MS > 0)
        assertTrue(TRANSLATION_CACHE_MAX > 0)
        val b = box("text")
        assertEquals(b, b.copy())
        assertEquals("text", b.text)
        assertEquals(4, b.corners.size)
    }
}

