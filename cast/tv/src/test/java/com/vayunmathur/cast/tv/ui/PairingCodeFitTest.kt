package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #919: on a Thomson Android TV the pairing screen showed "632" instead of "632  145", with the
 * bottoms of the digits cut off.
 *
 * Real text measurement (native graphics), so these check what the TV actually draws rather than
 * the semantics text, which is the full code however much of it is visible. Every configuration
 * is a TV screen as Android reports it - most panels, 720p, 1080p and 4K alike, come out as
 * 960x540 dp at their density - times a font scale. Font scale is applied linearly (no
 * Android 14 non-linear curve), which is the harsher case for 96 sp digits.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, qualifiers = "w1920dp-h1080dp-mdpi")
class PairingCodeFitTest {
    @get:Rule val rule = createComposeRule()

    private data class Screen(val name: String, val widthDp: Int, val heightDp: Int, val density: Float)

    private data class Case(val screen: Screen, val fontScale: Float) {
        override fun toString() = "${screen.name} ${screen.widthDp}x${screen.heightDp}dp fontScale=$fontScale"
    }

    private val screens = listOf(
        Screen("720p tvdpi", 960, 540, 1.33125f),
        Screen("1080p xhdpi", 960, 540, 2f),
        Screen("4K xxxhdpi", 960, 540, 4f),
        Screen("1080p hdpi", 1280, 720, 1.5f),
        Screen("1080p mdpi", 1920, 1080, 1f),
    )
    private val fontScales = listOf(1f, 1.15f, 1.3f, 1.5f, 2f)

    private var case by mutableStateOf(Case(screens.first(), 1f))
    private var sender by mutableStateOf("Pixel 6 Pro")
    private var codeChanged by mutableStateOf(false)

    private fun show() {
        rule.setContent {
            val c = case
            CompositionLocalProvider(LocalDensity provides Density(c.screen.density, c.fontScale)) {
                Box(Modifier.requiredSize(c.screen.widthDp.dp, c.screen.heightDp.dp).testTag(SCREEN)) {
                    CastTvTheme { PairingScreen(sender, CODE, attemptsLeft = 2, codeChanged = codeChanged) }
                }
            }
        }
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /** The whole code is drawn on one unclipped line, inside the overscan-safe area, at a size readable across a room. */
    private fun assertCodeFits(minGlyphDp: Float) {
        rule.waitForIdle()
        val screen = rule.onNodeWithTag(SCREEN).fetchSemanticsNode()
        val node = rule.onNodeWithText(SHOWN).fetchSemanticsNode()
        val layout = node.textLayout()
        val d = case.screen.density
        assertEquals(1, layout.lineCount, "$case: code wrapped")
        assertFalse(layout.didOverflowWidth, "$case: code wider than its card (${layout.size.width}px)")
        assertFalse(layout.didOverflowHeight, "$case: code dropped a line or overflowed its height")
        assertFalse(layout.hasVisualOverflow, "$case: code is clipped")
        assertEquals(SHOWN.length, layout.getLineEnd(0, visibleEnd = true), "$case: not every digit is on the visible line")
        val glyphPx = with(layout.layoutInput.density) { layout.layoutInput.style.fontSize.toPx() }
        val linePx = layout.getLineBottom(0) - layout.getLineTop(0)
        assertTrue(linePx >= glyphPx, "$case: line box ${linePx}px shorter than the ${glyphPx}px digits, so they are cut off")
        assertTrue(glyphPx / d >= minGlyphDp, "$case: digits only ${glyphPx / d}dp tall")
        val safe = Rect(
            screen.boundsInRoot.left + SAFE_H_DP * d - 1,
            screen.boundsInRoot.top + SAFE_V_DP * d - 1,
            screen.boundsInRoot.right - SAFE_H_DP * d + 1,
            screen.boundsInRoot.bottom - SAFE_V_DP * d + 1,
        )
        val drawn = node.boundsInRoot
        assertTrue(
            drawn.left >= safe.left && drawn.right <= safe.right && drawn.top >= safe.top && drawn.bottom <= safe.bottom,
            "$case: code $drawn outside the overscan-safe area $safe",
        )
    }

    private fun assertBelowCodeVisible(text: String) {
        val screen = rule.onNodeWithTag(SCREEN).fetchSemanticsNode().boundsInRoot
        val node = rule.onNodeWithText(text).fetchSemanticsNode()
        assertTrue(node.boundsInRoot.height > 0f && node.boundsInRoot.bottom <= screen.bottom, "$case: \"$text\" pushed off screen")
        assertFalse(node.textLayout().hasVisualOverflow, "$case: \"$text\" clipped")
    }

    @Test fun codeFitsEveryTvScreenAndFontScale() {
        show()
        for (screen in screens) for (scale in fontScales) {
            case = Case(screen, scale)
            assertCodeFits(minGlyphDp = 48f)
            assertBelowCodeVisible("2 attempts left")
        }
    }

    @Test fun codeFitsTheIssueTvAtDefaultFontScale() {
        // The configuration the reporter's Thomson set most likely runs: 1080p panel, xhdpi.
        show()
        case = Case(screens[1], 1f)
        assertCodeFits(minGlyphDp = 80f)
    }

    @Test fun codeSurvivesLongSenderNameAndChangedCodeNoticeAtLargestFontScale() {
        sender = "Living room projector belonging to the whole family upstairs (Pixel 6 Pro)"
        codeChanged = true
        show()
        for (screen in screens.filter { it.widthDp == 960 }) {
            case = Case(screen, 2f)
            assertCodeFits(minGlyphDp = 40f)
            assertBelowCodeVisible("That code was wrong three times, so here is a new one.")
        }
    }

    private companion object {
        const val CODE = "632145"
        const val SHOWN = "632  145"
        const val SCREEN = "screen"
        const val SAFE_H_DP = 48f
        const val SAFE_V_DP = 28f
    }
}
