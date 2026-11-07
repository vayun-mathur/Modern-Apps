package com.vayunmathur.games.chess

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.games.chess.auth.LichessTokenStore
import com.vayunmathur.games.chess.network.LichessApi
import com.vayunmathur.games.chess.network.LichessProfile
import com.vayunmathur.games.chess.network.LichessTransport
import com.vayunmathur.games.chess.platform.OnlineChessViewModel
import com.vayunmathur.games.chess.platform.OnlineConnection
import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pass 3: ChessOnlineScreens (ViewModel-backed) lobby interactions and PuzzleScreen. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChessPass3OnlineScreensVmTest {

    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx: Context get() = app
    private fun str(id: Int, vararg args: Any) =
        if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    private class ChannelStream : NetworkDataStream {
        private val channel = Channel<ByteArray>(Channel.UNLIMITED)
        private var current = ByteArray(0)
        private var pos = 0
        fun push(line: String) {
            channel.trySend((line + "\n").toByteArray())
        }
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= current.size) {
                current = channel.receive()
                pos = 0
            }
            val n = minOf(length, current.size - pos)
            current.copyInto(buffer, offset, pos, pos + n)
            pos += n
            return n
        }
        override val isClosedForRead: Boolean get() = false
    }

    private class FakeTransport : LichessTransport {
        val requests = mutableListOf<String>()
        var handler: (String) -> SimpleResponse = { SimpleResponse(200, "OK", "", emptyMap(), it) }
        val eventStream = ChannelStream()
        val gameStream = ChannelStream()

        override suspend fun request(url: String, method: String, headers: Map<String, String>, body: String?): SimpleResponse {
            requests += url
            return handler(url)
        }

        override suspend fun account(url: String, headers: Map<String, String>) = LichessProfile("me-id", "meuser")

        override suspend fun stream(
            url: String,
            headers: Map<String, String>,
            readTimeoutMs: Long,
            block: suspend (NetworkDataStream?, SimpleResponse) -> Unit,
        ) {
            val stream = if (url.contains("/stream/event")) eventStream else gameStream
            block(stream, SimpleResponse(200, "OK", "", emptyMap(), url))
        }
    }

    private lateinit var store: LichessTokenStore
    private lateinit var fake: FakeTransport

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        store = LichessTokenStore(app)
        runBlocking {
            store.clear()
            store.saveToken("tok")
            store.saveUsername("meuser")
            store.saveUserId("me-id")
        }
        fake = FakeTransport()
    }

    @AfterTest fun tearDown() {
        runBlocking { store.clear() }
        Dispatchers.resetMain()
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.waitForIdle()
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    @Test fun onlineScreensLobbySeekCancelAndChallenge() {
        val vm = OnlineChessViewModel(app)
        vm.api = LichessApi(store, fake)
        await("signed in") { vm.uiState.value.auth is com.vayunmathur.games.chess.platform.OnlineAuth.SignedIn }
        rule.setContent { ChessOnlineScreens(vm) }
        rule.onNodeWithText(str(R.string.online_signed_in_as, "meuser"), substring = true).assertExists()

        // Time-control chips render (the row is disabled while Robolectric reports
        // offline, so selection is driven through the VM, as the screen would).
        rule.onNodeWithText(str(R.string.online_tc_10_0)).assertExists()
        vm.selectTimeControl(com.vayunmathur.games.chess.network.LichessTimeControl.RAPID_10_0)
        assertEquals(
            com.vayunmathur.games.chess.network.LichessTimeControl.RAPID_10_0,
            vm.uiState.value.selectedTimeControl,
        )

        // Quick seek posts and shows the seeking row; the on-screen Cancel idles.
        vm.quickSeek()
        await("seeking") { vm.uiState.value.connection is OnlineConnection.Seeking }
        assertTrue(fake.requests.any { it.contains("/api/board/seek") })
        rule.onNodeWithText(str(R.string.online_seeking, "10+0"), substring = true).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)

        // Challenge by typing a name into the field and pressing Challenge.
        rule.onNode(hasSetTextAction()).performTextInput("rival")
        assertEquals("rival", vm.uiState.value.challengeUsername)
        vm.challenge()
        await("challenging") { vm.uiState.value.connection is OnlineConnection.Challenging }
        assertTrue(fake.requests.any { it.contains("/api/board/challenge/rival") })
        vm.signOut()
    }

    @Test fun onlineScreensGameBoardResignFlow() {
        val vm = OnlineChessViewModel(app)
        vm.api = LichessApi(store, fake)
        await("signed in") { vm.uiState.value.auth is com.vayunmathur.games.chess.platform.OnlineAuth.SignedIn }
        rule.setContent { ChessOnlineScreens(vm) }
        fake.eventStream.push(
            """{"type":"gameStart","game":{"gameId":"g7","color":"white","opponent":{"username":"Opp"}} }""".trim()
        )
        await("playing") { vm.uiState.value.connection is OnlineConnection.Playing }
        fake.gameStream.push(
            """{"type":"gameFull","id":"g7","white":{"id":"me-id","name":"Me"},"black":{"id":"op","name":"Opp"},"state":{"type":"gameState","moves":"","wtime":300000,"btime":300000,"status":"started"}}"""
        )
        await("clocks") {
            (vm.uiState.value.connection as? OnlineConnection.Playing)?.whiteClockMs == 300000L
        }
        rule.onNodeWithText(str(R.string.online_opponent, "Opp"), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.online_resign_confirm)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.online_resign_title)).assertExists()
        val confirms = rule.onAllNodesWithText(str(R.string.online_resign_confirm))
        confirms[confirms.fetchSemanticsNodes().size - 1].performClick()
        await("resign posted") { fake.requests.any { it.contains("/api/board/game/g7/resign") } }
        vm.signOut()
    }

    @Test fun puzzleScreenLoadsAndShowsBoard() {
        val vm = com.vayunmathur.games.chess.util.PuzzleViewModel(app)
        rule.setContent { PuzzleScreen(vm) }
        await("puzzle solving") {
            vm.uiState.value.status == com.vayunmathur.games.chess.util.PuzzleStatus.Solving ||
                vm.uiState.value.status == com.vayunmathur.games.chess.util.PuzzleStatus.Solved
        }
        // Rating line, to-move line, status, and next-puzzle button are on screen.
        rule.onNodeWithText(str(R.string.puzzle_rating, vm.uiState.value.rating), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.puzzle_next), substring = true).assertExists()
        assertTrue(vm.uiState.value.puzzle != null)
    }
}
