package com.vayunmathur.appstore.tv

import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TV card art and stage labels: every install stage has a label, and
 * the art slot renders the installed icon when there is one and the
 * app's initial otherwise.
 */
@RunWith(RobolectricTestRunner::class)
class TvCardArtAndStageTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `every stage has a distinct label`() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                Text(InstallStage.Preparing.tvStageLabel())
                Text(InstallStage.Downloading(0.25f).tvStageLabel())
                Text(InstallStage.Verifying.tvStageLabel())
                Text(InstallStage.Installing.tvStageLabel())
                Text(InstallStage.Failed("nope").tvStageLabel())
            }
        }
        rule.waitForIdle()
        for (expected in listOf(
            "Preparing…", "Downloading 25%", "Checking the download…",
            "Installing…", "Failed: nope",
        )) {
            rule.onNodeWithText(expected).assertIsDisplayed()
        }
    }

    @Test
    fun `art falls back to the app initial without any icon`() {
        val app = UnifiedApp("com.example.zed", AppSource.FDROID, "Zed App")
        rule.setContent {
            Box { TvStoreAppArt(app = app, installedIcon = null) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Z").assertIsDisplayed()
    }

    @Test
    fun `art renders with an installed icon`() {
        val app = UnifiedApp("com.example.zed", AppSource.FDROID, "Zed App")
        rule.setContent {
            Box {
                TvStoreAppArt(
                    app = app,
                    installedIcon = ColorDrawable(android.graphics.Color.MAGENTA),
                )
            }
        }
        rule.waitForIdle()
        // No initial letter is shown when a real icon exists.
        assertTrue(
            rule.onAllNodesWithText("Z").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `stage label can render as text`() {
        rule.setContent { Text(InstallStage.Verifying.tvStageLabel()) }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("Checking the download", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
