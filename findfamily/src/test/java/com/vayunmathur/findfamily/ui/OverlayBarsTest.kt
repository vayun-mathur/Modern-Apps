package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.util.MainPageActions
import com.vayunmathur.findfamily.util.MainPageUiState
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/** Robolectric Compose tests for [MapOverlayBar], [MainFabOverlay] and [FindFamilyWideLayout]. */
@RunWith(AndroidJUnit4::class)
class OverlayBarsTest {
    @get:Rule val rule = createComposeRule()

    private fun user(id: Long) = User(name = "Alice", photo = null, locationName = "Home",
        sendingEnabled = true, requestStatus = RequestStatus.MUTUAL_CONNECTION, id = id)

    @Test fun overlayBarNothingSelected() {
        rule.setContent { MapOverlayBar(MainPageUiState(usingGpsFallback = true), MainPageActions.Noop, "pw") }
        rule.waitForIdle()
    }
    @Test fun overlayBarWithoutGpsFallback() {
        // Selection-scoped overlay actions moved to the sheet destinations;
        // the map overlay renders in its only (unselected) shape.
        rule.setContent { MapOverlayBar(MainPageUiState(), MainPageActions.Noop, "pw") }
        rule.waitForIdle()
    }
    @Test fun fabOverlayNothingSelected() {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                MainFabOverlay(MainPageUiState(), MainPageActions.Noop, 80.dp, { 0 },
                    MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.onPrimaryContainer, lightColorScheme(), inPreview = false)
            }
        }
        rule.waitForIdle()
    }
    @Test fun fabOverlayInPreview() {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                MainFabOverlay(MainPageUiState(), MainPageActions.Noop, 80.dp, { 0 },
                    MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.onPrimaryContainer, lightColorScheme(), inPreview = true)
            }
        }
        rule.waitForIdle()
    }
    @Test fun wideLayoutRendersBothPanes() {
        rule.setContent {
            FindFamilyWideLayout(map = { androidx.compose.material3.Text("MAP", it) }, panel = { androidx.compose.material3.Text("PANEL", it) })
        }
        rule.onNodeWithText("MAP").assertIsDisplayed()
        rule.onNodeWithText("PANEL").assertIsDisplayed()
    }
}
