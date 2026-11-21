package com.vayunmathur.games.kart.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.kart.R
import com.vayunmathur.games.kart.data.CUPS
import com.vayunmathur.games.kart.platform.KartGameActions
import com.vayunmathur.games.kart.platform.KartUiState
import com.vayunmathur.games.kart.platform.RacePhase
import com.vayunmathur.sdk.controller.LobbyState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

/**
 * A landscape phone: short enough that the old all-phases scrolling column
 * put part of the race HUD below the fold. The race surface must now be a
 * fixed layout — no scrollable node to drag mid-race — while the garage
 * keeps its scroll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h360dp-land")
class KartRaceSurfaceTest {

    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)

    @Test fun racingHasNoScrollAndShowsStandings() {
        val phase = RacePhase.Racing(
            order = listOf("p1", "ai-0", "ai-1", "ai-2"),
            lapProgress = mapOf("p1" to 0.5f, "ai-0" to 0.25f),
            playerLap = 1,
            totalLaps = 3,
            lastLapMs = 83_456L,
            bestLapMs = 61_005L,
        )
        rule.setContent { KartGameScreen(KartUiState(cup = CUPS[0]), phase, KartGameActions.Noop) }
        rule.onNode(hasScrollAction()).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.race_lap, 2, 3)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.race_place, 4, "Rival 2")).assertIsDisplayed()
    }

    @Test fun countdownHasNoScroll() {
        rule.setContent {
            KartGameScreen(KartUiState(cup = CUPS[0]), RacePhase.Countdown(2), KartGameActions.Noop)
        }
        rule.onNode(hasScrollAction()).assertDoesNotExist()
        rule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test fun lobbyStillScrolls() {
        rule.setContent {
            KartGameScreen(
                KartUiState(cup = CUPS[0]),
                RacePhase.Lobby(LobbyState(gameId = "kart")),
                KartGameActions.Noop,
            )
        }
        rule.onNode(hasScrollAction()).assertExists()
    }
}
