package com.vayunmathur.library.network

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.vayunmathur.library.util.OfflineBanner
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Forces an actual draw of the offline banner so the Canvas warning glyph
 * renders, not just composes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineGlyphDrawTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun offlineBannerDrawsWarningGlyph() {
        composeRule.setContent { OfflineBanner(online = false) }
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width > 0)
        assertTrue(bitmap.height > 0)
    }
}
