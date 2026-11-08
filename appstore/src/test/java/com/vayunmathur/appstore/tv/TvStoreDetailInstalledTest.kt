package com.vayunmathur.appstore.tv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TV detail primary-action states: installed offers Open + Uninstall,
 * an update offers Update + Open, and neither is offered while a
 * download is busy. (TV buttons are focus-first D-pad controls, so
 * this asserts rendering, not clicks — see TvStoreScreensTest.)
 */
@RunWith(RobolectricTestRunner::class)
class TvStoreDetailInstalledTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(versionCode: Long = 2L) = UnifiedApp(
        packageName = "com.example.installed",
        source = AppSource.FDROID,
        name = "Installed App",
        versionName = "2.0",
        versionCode = versionCode,
    )

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `installed app offers open and uninstall`() {
        val state = TvDetailUiState(
            app = app(),
            installedInfo = InstalledInfo("com.example.installed", "Installed App", "1.0", 2L),
        )
        rule.setContent { TvStoreDetailScreen(state = state) }
        assertTrue(exists("Open"))
        assertTrue(exists("Uninstall"))
    }

    @Test
    fun `an available update offers update and open`() {
        val state = TvDetailUiState(
            app = app(versionCode = 3L),
            installedInfo = InstalledInfo("com.example.installed", "Installed App", "1.0", 2L),
        )
        rule.setContent { TvStoreDetailScreen(state = state) }
        assertTrue(exists("Update"))
        assertTrue(exists("Open"))
    }

    @Test
    fun `a not installed app offers install only`() {
        rule.setContent { TvStoreDetailScreen(state = TvDetailUiState(app = app())) }
        assertTrue(exists("Install"))
        assertTrue(!exists("Uninstall"))
    }
}
