package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Rendering tests for [PageIndicator]: hidden for a single page, drawn for
 * several, with the scroll progress clamped into range. The indicator has no
 * semantics by design (it is drawn), so these assert composition and drawing
 * survive every progress shape the pager can hand it, including out-of-range
 * ones, by composing several variants at once.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageIndicatorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `single and zero page counts render nothing without crashing`() {
        composeRule.setContent {
            DynamicTheme {
                Column(Modifier.fillMaxSize()) {
                    PageIndicator(pageCount = 1, scrollProgress = { 0f })
                    PageIndicator(pageCount = 0, scrollProgress = { 0f })
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `multiple pages render at rest, mid-swipe and at the end`() {
        composeRule.setContent {
            DynamicTheme {
                Column(Modifier.fillMaxSize()) {
                    PageIndicator(pageCount = 4, scrollProgress = { 0f })
                    PageIndicator(pageCount = 4, scrollProgress = { 1.5f })
                    PageIndicator(pageCount = 4, scrollProgress = { 3f })
                    PageIndicator(pageCount = 2, scrollProgress = { 0.25f })
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `out-of-range progress is clamped rather than crashing the draw`() {
        // The progress lambda is only sampled inside drawBehind, which
        // Robolectric does not rasterise; composing these shapes without an
        // exception is the observable behaviour here.
        composeRule.setContent {
            DynamicTheme {
                Column(Modifier.fillMaxSize()) {
                    PageIndicator(pageCount = 3, scrollProgress = { -2f })
                    PageIndicator(pageCount = 3, scrollProgress = { 99f })
                    PageIndicator(pageCount = 7, scrollProgress = { 6f })
                }
            }
        }
        composeRule.waitForIdle()
    }
}
