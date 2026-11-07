package com.vayunmathur.uniflix.ui.tv

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.uniflix.data.AddonEntity
import com.vayunmathur.uniflix.data.AddonPrefs
import com.vayunmathur.uniflix.data.RemoteImageFetchers
import com.vayunmathur.uniflix.domain.SubtitleTrack
import com.vayunmathur.uniflix.platform.AddonRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TV picker dialogs, TV addon card, addon prefs, and image-fetcher routing. */
@RunWith(RobolectricTestRunner::class)
class TvPickersAndPrefsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun resolutionLabels() {
        assertEquals("4K", resolutionLabel(2160))
        assertEquals("1080p", resolutionLabel(1080))
        assertEquals("720p", resolutionLabel(720))
        assertEquals("480p", resolutionLabel(480))
        assertEquals("360p", resolutionLabel(360))
        assertEquals("4K", resolutionLabel(4000))
    }

    @Test fun serverPickerSelectsRow() {
        var selected: String? = null
        composeRule.setContent {
            DynamicTheme {
                TvServerPickerDialog(
                    servers = listOf("Alpha", "Beta"),
                    selectedTitle = "Alpha",
                    onDismiss = {},
                    onSelect = { selected = it })
            }
        }
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Beta").assertIsDisplayed()
        composeRule.onNode(
            androidx.compose.ui.test.hasText("Beta") and hasClickAction())
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals("Beta", selected)
    }

    @Test fun subtitlePickerOffersOffAndTracks() {
        var selected: SubtitleTrack? = SubtitleTrack("en", "English", "https://x/en.vtt")
        var touched = false
        composeRule.setContent {
            DynamicTheme {
                TvSubtitlePickerDialog(
                    subtitles = listOf(SubtitleTrack("en", "English", "https://x/en.vtt")),
                    selected = selected,
                    onDismiss = {},
                    onSelect = { selected = it; touched = true })
            }
        }
        composeRule.onNodeWithText("English").assertIsDisplayed()
        composeRule.onNode(
            androidx.compose.ui.test.hasText("Off") and hasClickAction())
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertTrue(touched)
        assertNull(selected)
    }

    @Test fun qualityPickerSelectsHeight() {
        var selected: Int? = null
        var touched = false
        composeRule.setContent {
            DynamicTheme {
                TvQualityPickerDialog(
                    heights = listOf(1080, 720),
                    selectedHeight = null,
                    onDismiss = {},
                    onSelect = { selected = it; touched = true })
            }
        }
        composeRule.onNodeWithText("1080p").assertIsDisplayed()
        composeRule.onNode(
            androidx.compose.ui.test.hasText("720p") and hasClickAction())
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertTrue(touched)
        assertEquals(720, selected)
    }

    @Test fun tvAddonCardShowsBadgesAndError() {
        var opened = 0
        val entity = AddonEntity(
            dbId = 2, manifestUrl = "https://a.example/manifest.json",
            baseUrl = "https://a.example", addonId = "a", name = "Alpha",
            version = "2.0", enabled = false, lastError = "broken", kind = "js")
        composeRule.setContent {
            DynamicTheme {
                TvAddonCard(row = AddonRow(entity, isDefault = true), onOpenDetail = { opened++ })
            }
        }
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("broken").assertIsDisplayed()
        composeRule.onNode(hasClickAction())
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test fun addonPrefsDefaultRoundTrip() = runTest {
        val prefs = AddonPrefs(context)
        prefs.setDefaultAddonDbId(null)
        assertNull(prefs.defaultAddonDbId.first())
        prefs.setDefaultAddonDbId(77L)
        assertEquals(77L, prefs.defaultAddonDbId.first())
        prefs.setDefaultAddonDbId(null)
        assertNull(prefs.defaultAddonDbId.first())
    }

    @Test fun remoteFetcherRejectsNonHttpData() = runTest {
        assertNull(RemoteImageFetchers.network(null, context))
        assertNull(RemoteImageFetchers.network(42, context))
        assertNull(RemoteImageFetchers.network("file:///tmp/x.png", context))
        assertNull(RemoteImageFetchers.network(Uri.parse("content://media/1"), context))
        assertNull(RemoteImageFetchers.network(Uri.parse("file:///tmp/x.png"), context))
        // An unreachable https URL fails (throw or IOException), never returns bytes.
        assertTrue(runCatching { RemoteImageFetchers.network("https://addons.invalid/x.png", context) }.isFailure)
    }
}
