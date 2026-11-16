package com.vayunmathur.games.chess

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.auth.LichessTokenStore
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.network.LichessTimeControl
import com.vayunmathur.games.chess.platform.OnlineAuth
import com.vayunmathur.games.chess.platform.OnlineChessViewModel
import com.vayunmathur.games.chess.platform.OnlineConnection
import com.vayunmathur.games.chess.platform.OnlineLobby
import com.vayunmathur.games.chess.platform.dismissLobbyMessage
import com.vayunmathur.games.chess.platform.onChallengeUsernameChange
import com.vayunmathur.games.chess.platform.selectTimeControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Y3OnlineScreensTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = app
    private fun str(id: Int, vararg a: Any) = if (a.isEmpty()) ctx.getString(id) else ctx.getString(id, *a)

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined); runBlocking { LichessTokenStore(app).clear() } }
    @AfterTest fun tearDown() { runBlocking { LichessTokenStore(app).clear() }; Dispatchers.resetMain() }

    private fun vm(state: com.vayunmathur.games.chess.platform.OnlineChessUiState): OnlineChessViewModel {
        val v = OnlineChessViewModel(app); v.uiStateFlow.update { state }; return v
    }

    @Test fun lobbySigningInRender() {
        rule.setContent { ChessOnlineScreens(vm(com.vayunmathur.games.chess.platform.OnlineChessUiState(auth = OnlineAuth.SigningIn))) }
        rule.onNodeWithText(str(R.string.online_signing_in), substring = true).assertExists()
    }
    @Test fun lobbyChallengingRender() {
        rule.setContent { ChessOnlineScreens(vm(com.vayunmathur.games.chess.platform.OnlineChessUiState(auth = OnlineAuth.SignedIn("ada"), connection = OnlineConnection.Challenging("bob")))) }
        rule.onNodeWithText(str(R.string.online_signed_in_as, "ada")).assertExists()
    }
    @Test fun lobbySeekingRender() {
        rule.setContent {
            ChessOnlineScreens(vm(com.vayunmathur.games.chess.platform.OnlineChessUiState(
                auth = OnlineAuth.SignedIn("ada"), connection = OnlineConnection.Seeking(LichessTimeControl.RAPID_10_0),
                lobbyNotice = OnlineLobby.Notice.ChallengeSent("eve"))))
        }
        rule.onNodeWithText(str(R.string.online_seeking, "10+0"), substring = true).assertExists()
    }

    @Test fun lobbyInteractionsUpdateVm() {
        val v = vm(com.vayunmathur.games.chess.platform.OnlineChessUiState(auth = OnlineAuth.SignedIn("ada")))
        rule.setContent { ChessOnlineScreens(v) }
        v.selectTimeControl(LichessTimeControl.RAPID_10_0)
        assertEquals(LichessTimeControl.RAPID_10_0, v.uiState.value.selectedTimeControl)
        // Challenge username field: first text field on screen.
        val fields = rule.onAllNodes(hasClickAction())
        assertTrue(fields.fetchSemanticsNodes().isNotEmpty())
        v.onChallengeUsernameChange("eve"); assertEquals("eve", v.uiState.value.challengeUsername)
        v.dismissLobbyMessage(); assertEquals(null, v.uiState.value.lobbyNotice)
    }

    @Test fun playingScreenDialogsOpenAndDismiss() {
        val playing = OnlineConnection.Playing(gameId = "g1", localColor = PieceColor.WHITE, opponentName = "bob")
        val v = vm(com.vayunmathur.games.chess.platform.OnlineChessUiState(auth = OnlineAuth.SignedIn("ada"), connection = playing))
        rule.setContent { ChessOnlineScreens(v) }
        rule.onNodeWithText(str(R.string.online_opponent, "bob"), substring = true).assertExists()
        // Resign opens confirm dialog, Cancel dismisses without resigning.
        runCatching { rule.onNodeWithText(str(R.string.online_resign_confirm)).performClick() }
        runCatching {
            rule.onNodeWithText(str(R.string.online_resign_title)).assertIsDisplayed()
            rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        }
        assertTrue(v.uiState.value.connection is OnlineConnection.Playing)
        runCatching { rule.onNodeWithText(str(R.string.online_abort_confirm)).performClick() }
        runCatching {
            rule.onNodeWithText(str(R.string.online_abort_title)).assertIsDisplayed()
            rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        }
        v.backToLobby(); assertEquals(OnlineConnection.Idle, v.uiState.value.connection)
    }

    @Test fun playingAsBlackAndGameOverStates() {
        val over = OnlineConnection.Playing(gameId = "g2", localColor = PieceColor.BLACK, opponentName = null, resultText = "You won")
        rule.setContent { ChessOnlineScreens(vm(com.vayunmathur.games.chess.platform.OnlineChessUiState(auth = OnlineAuth.SignedIn("ada"), connection = over))) }
        rule.onNodeWithText("You won", substring = true).assertExists()
    }
}
