package com.vayunmathur.games.chess

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.network.LichessTimeControl
import com.vayunmathur.games.chess.platform.OnlineAuth
import com.vayunmathur.games.chess.platform.OnlineChessUiState
import com.vayunmathur.games.chess.platform.OnlineChessViewModel
import com.vayunmathur.games.chess.platform.OnlineConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnlineScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun `signed out lobby shows lichess sign in`() {
        rule.setContent { ChessOnlineScreen(OnlineChessUiState()) }
        rule.onNodeWithText(str(R.string.online_sign_in)).assertExists()
    }

    @Test fun `signed in lobby shows username and quick seek`() {
        rule.setContent {
            ChessOnlineScreen(OnlineChessUiState(auth = OnlineAuth.SignedIn("ada")))
        }
        rule.onNodeWithText(str(R.string.online_signed_in_as, "ada")).assertExists()
        rule.onNodeWithText(str(R.string.online_quick_seek)).assertExists()
    }

    @Test fun `seeking lobby shows seeking label`() {
        rule.setContent {
            ChessOnlineScreen(OnlineChessUiState(
                auth = OnlineAuth.SignedIn("ada"),
                connection = OnlineConnection.Seeking(LichessTimeControl.BLITZ_3_2),
            ))
        }
        rule.onNodeWithText(str(R.string.online_seeking, "3+2"), substring = true).assertExists()
    }

    @Test fun `playing state renders board and derived flags follow color`() {
        val playing = OnlineConnection.Playing(gameId = "g1", localColor = PieceColor.BLACK, opponentName = "bob")
        val state = OnlineChessUiState(auth = OnlineAuth.SignedIn("ada"), connection = playing)
        assertTrue(state.isBoardFlipped)
        assertEquals(PieceColor.WHITE, state.turn)
        assertTrue(playing.canAbort)
        rule.setContent { ChessOnlineScreen(state) }
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(str(R.string.piece_white_pawn), substring = true)).assertCountEquals(8)
    }

    @Test fun `viewmodel selects time control and challenge name`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = OnlineChessViewModel(app)
        assertEquals(OnlineAuth.SignedOut, vm.uiState.value.auth)
        vm.selectTimeControl(LichessTimeControl.RAPID_10_0)
        assertEquals(LichessTimeControl.RAPID_10_0, vm.uiState.value.selectedTimeControl)
        vm.onChallengeUsernameChange("bob")
        assertEquals("bob", vm.uiState.value.challengeUsername)
        assertEquals(str(R.string.online_sign_in_failed), vm.authErrorText(OnlineAuth.Error.SignInFailed))
        vm.signOut()
        assertEquals(OnlineAuth.SignedOut, vm.uiState.value.auth)
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
        assertNull(vm.uiState.value.authError)
    }
}
