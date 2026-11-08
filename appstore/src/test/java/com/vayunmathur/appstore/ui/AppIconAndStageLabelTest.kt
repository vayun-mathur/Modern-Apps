package com.vayunmathur.appstore.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AppIcon's three render paths and every install-stage label: an
 * installed drawable wins, a remote URL is handed to the image
 * pipeline, and with neither the app's initial is drawn on a tile.
 */
@RunWith(RobolectricTestRunner::class)
class AppIconAndStageLabelTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(name: String = "Example", iconUrl: String? = null) = UnifiedApp(
        packageName = "com.example.app",
        source = AppSource.FDROID,
        name = name,
        iconUrl = iconUrl,
    )

    @Test
    fun `icon with no artwork anywhere draws the app initial`() {
        rule.setContent { AppIcon(app = app(name = "Zebra"), installedIcon = null) }

        rule.onNodeWithText("Z").assertIsDisplayed()
    }

    @Test
    fun `icon with an installed drawable renders`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val drawable = BitmapDrawable(context.resources, bitmap)
        rule.setContent { AppIcon(app = app(), installedIcon = drawable) }

        rule.waitForIdle()
        // The bitmap path draws an Image with no text; the initial tile must not appear.
        assertTrue(
            rule.onAllNodes(
                androidx.compose.ui.test.hasText("E"),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `every install stage has its own label`() {
        val labels = mutableListOf<String>()
        rule.setContent {
            labels += stageLabel(InstallStage.Preparing)
            labels += stageLabel(InstallStage.Downloading(0.25f))
            labels += stageLabel(InstallStage.Verifying)
            labels += stageLabel(InstallStage.Installing)
            labels += stageLabel(InstallStage.Failed("hash mismatch"))
        }
        rule.waitForIdle()

        assertEquals(5, labels.size)
        assertTrue(labels.all { it.isNotBlank() }, labels.toString())
        assertTrue(labels[1].contains("25"), labels.toString())
        assertTrue(labels[4].contains("hash mismatch"), labels.toString())
        assertEquals(labels.size, labels.toSet().size, labels.toString())
    }
}
