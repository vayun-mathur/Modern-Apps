package com.vayunmathur.uniflix.ui.tv

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.uniflix.ui.WatchPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TV watch overlay and TV add-addon dialog with a player-less state holder. */
@RunWith(RobolectricTestRunner::class)
class TvWatchOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun playerState(): WatchPlayerState = WatchPlayerState(
        player = null,
        positionState = mutableLongStateOf(65_000L),
        bufferedState = mutableLongStateOf(80_000L),
        durationState = mutableLongStateOf(600_000L),
        playingState = mutableStateOf(false),
        bufferingState = mutableStateOf(false),
        draggingState = mutableStateOf(false),
        cuesState = mutableStateOf(emptyList()),
        heightsState = mutableStateOf(listOf(1080)),
        errorState = mutableStateOf<String?>(null),
    )

    @Test fun overlayShowsTitleChipsAndTimes() {
        var server = 0
        var subs = 0
        var quality = 0
        composeRule.setContent {
            DynamicTheme {
                TvWatchOverlay(
                    title = "The Show",
                    serverLabel = "Alpha",
                    showServerPicker = true,
                    onOpenServerPicker = { server++ },
                    playerState = playerState(),
                    subtitleLabel = "English",
                    showSubtitlePicker = true,
                    onOpenSubtitlePicker = { subs++ },
                    qualityLabel = "1080p",
                    showQualityPicker = true,
                    onOpenQualityPicker = { quality++ },
                    playFocus = remember { FocusRequester() },
                    onBack = {})
            }
        }
        composeRule.onNodeWithText("The Show").assertIsDisplayed()
        composeRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("English").assertIsDisplayed()
        composeRule.onNodeWithText("1080p").assertIsDisplayed()
        composeRule.onNodeWithText("1:05").assertIsDisplayed()
        composeRule.onNodeWithText("10:00").assertIsDisplayed()
        composeRule.onNode(androidx.compose.ui.test.hasText("Alpha") and hasClickAction())
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(1, server)
        assertEquals(0, subs)
        assertEquals(0, quality)
    }

    @Test fun overlayHidesChipsWhenPickersDisabled() {
        composeRule.setContent {
            DynamicTheme {
                TvWatchOverlay(
                    title = "Film",
                    serverLabel = "Alpha",
                    showServerPicker = false,
                    onOpenServerPicker = {},
                    playerState = playerState(),
                    subtitleLabel = "English",
                    showSubtitlePicker = false,
                    onOpenSubtitlePicker = {},
                    qualityLabel = "1080p",
                    showQualityPicker = false,
                    onOpenQualityPicker = {},
                    playFocus = remember { FocusRequester() },
                    onBack = {})
            }
        }
        composeRule.onNodeWithText("Film").assertIsDisplayed()
        composeRule.onNodeWithText("1:05").assertIsDisplayed()
    }

    @Test fun tvAddAddonDialogConfirmsUrl() {
        var confirmed: String? = null
        var imports = 0
        composeRule.setContent {
            DynamicTheme {
                TvAddAddonDialog(
                    adding = false,
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                    onImportJs = { imports++ })
            }
        }
        composeRule.onNodeWithText("Add addon").assertIsDisplayed()
        // The dialog's text field is the only editable node.
        composeRule.onNode(
            androidx.compose.ui.test.hasSetTextAction())
            .performTextInput("https://addons.example/manifest.json")
        composeRule.onNode(androidx.compose.ui.test.hasText("Import .js") and hasClickAction())
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(1, imports)
        composeRule.onNode(androidx.compose.ui.test.hasText("Add") and hasClickAction())
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals("https://addons.example/manifest.json", confirmed)
    }
}
