package com.vayunmathur.appstore.tv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TV card badge precedence and labels: update outranks installed, a
 * failed stage shows its reason instead, and the source/rating/
 * reproducible pills come from the listing itself.
 */
@RunWith(RobolectricTestRunner::class)
class TvStoreCardsRichTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(
        source: AppSource = AppSource.FDROID,
        rating: Float? = null,
        reproducible: Boolean = false,
        author: String? = null,
    ) = UnifiedApp(
        packageName = "com.example.card",
        source = source,
        name = "Card App",
        summary = "Card summary",
        author = author,
        rating = rating,
        reproducible = reproducible,
    )

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `card shows source rating and reproducible badges`() {
        rule.setContent {
            TvStoreAppCard(app = app(rating = 4.5f, reproducible = true), onClick = {})
        }
        assertTrue(exists("Card App"))
        assertTrue(exists("F-Droid"))
        assertTrue(exists("4.5"))
        assertTrue(exists("Reproducible"))
    }

    @Test
    fun `update badge outranks the installed marker`() {
        rule.setContent {
            TvStoreAppCard(app = app(), onClick = {}, isInstalled = true, hasUpdate = true)
        }
        assertTrue(exists("Update"))
    }

    @Test
    fun `installed marker shows when there is no update`() {
        rule.setContent {
            TvStoreAppCard(app = app(), onClick = {}, isInstalled = true)
        }
        assertTrue(exists("Installed"))
    }

    @Test
    fun `a failed stage shows its reason on the card`() {
        rule.setContent {
            TvStoreAppCard(
                app = app(),
                onClick = {},
                stage = InstallStage.Failed("disk full"),
            )
        }
        assertTrue(exists("disk full"))
    }

    @Test
    fun `author beats the source as the card subtitle`() {
        rule.setContent {
            TvStoreAppCard(app = app(author = "Card Author"), onClick = {})
        }
        assertTrue(exists("Card Author"))
    }

    @Test
    fun `download fraction only exists for downloading and is clamped`() {
        assertEquals(0.5f, InstallStage.Downloading(0.5f).downloadFraction())
        assertEquals(1f, InstallStage.Downloading(1.5f).downloadFraction())
        assertEquals(0f, InstallStage.Downloading(-1f).downloadFraction())
        assertNull(InstallStage.Installing.downloadFraction())
        assertNull(InstallStage.Failed("x").downloadFraction())
        assertNull(InstallStage.Preparing.downloadFraction())
    }
}
