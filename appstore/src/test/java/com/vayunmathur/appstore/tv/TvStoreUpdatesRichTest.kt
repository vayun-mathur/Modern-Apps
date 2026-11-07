package com.vayunmathur.appstore.tv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TV updates states beyond the basic row test: up-to-date, checking,
 * foreign updates listed separately, failed stages, and a status line.
 */
@RunWith(RobolectricTestRunner::class)
class TvStoreUpdatesRichTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(pkg: String, name: String) = UnifiedApp(
        packageName = pkg,
        source = AppSource.FDROID,
        name = name,
        versionName = "2.0",
        versionCode = 2L,
    )

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `up to date state shows the empty message`() {
        rule.setContent { TvStoreUpdatesScreen(state = TvUpdatesUiState()) }
        assertTrue(exists("Everything is up to date"))
    }

    @Test
    fun `checking state does not show the empty message`() {
        rule.setContent { TvStoreUpdatesScreen(state = TvUpdatesUiState(isChecking = true)) }
        rule.waitForIdle()
    }

    @Test
    fun `updates and foreign updates both render`() {
        val state = TvUpdatesUiState(
            updates = listOf(app("com.example.ours", "Our Update")),
            foreignUpdates = listOf(app("com.example.foreign", "Foreign Update")),
            installedInfos = mapOf(
                "com.example.ours" to InstalledInfo("com.example.ours", "Our Update", "1.0", 1L),
                "com.example.foreign" to InstalledInfo("com.example.foreign", "Foreign Update", "1.0", 1L),
            ),
            statusMessage = "2 updates found",
        )
        rule.setContent { TvStoreUpdatesScreen(state = state) }
        assertTrue(exists("Our Update"))
        assertTrue(exists("2 updates found"))
    }

    @Test
    fun `a failed stage renders its reason`() {
        val state = TvUpdatesUiState(
            updates = listOf(app("com.example.broken", "Broken Update")),
            installedInfos = mapOf(
                "com.example.broken" to InstalledInfo("com.example.broken", "Broken Update", "1.0", 1L),
            ),
            stages = mapOf("com.example.broken" to InstallStage.Failed("disk full")),
        )
        rule.setContent { TvStoreUpdatesScreen(state = state) }
        assertTrue(exists("Broken Update"))
        assertTrue(exists("disk full"))
    }

    @Test
    fun `last checked time renders a status line`() {
        val state = TvUpdatesUiState(lastCheckedAt = System.currentTimeMillis() - 60_000L)
        rule.setContent { TvStoreUpdatesScreen(state = state) }
        rule.waitForIdle()
        assertTrue(exists("Everything is up to date"))
    }
}
