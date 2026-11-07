package com.vayunmathur.games.chess.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.auth.LichessTokenStore
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.network.LichessApi
import com.vayunmathur.games.chess.network.LichessProfile
import com.vayunmathur.games.chess.network.LichessTimeControl
import com.vayunmathur.games.chess.network.LichessTransport
import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.SimpleResponse
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pass 3: OnlineChessViewModel driven end-to-end by a scripted Lichess transport. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnlineChessPass3Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /** NDJSON stream fed line-by-line by the test; never EOFs (suspends when empty). */
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
        var streamStatus = 200

        override suspend fun request(url: String, method: String, headers: Map<String, String>, body: String?): SimpleResponse {
            requests += "$method $url"
            return handler(url)
        }

        override suspend fun account(url: String, headers: Map<String, String>): LichessProfile =
            LichessProfile("me-id", "meuser")

        override suspend fun stream(
            url: String,
            headers: Map<String, String>,
            readTimeoutMs: Long,
            block: suspend (NetworkDataStream?, SimpleResponse) -> Unit,
        ) {
            if (streamStatus !in 200..299) {
                block(null, SimpleResponse(streamStatus, "", "err", emptyMap(), url))
                return
            }
            val stream = if (url.contains("/stream/event")) eventStream else gameStream
            block(stream, SimpleResponse(200, "OK", "", emptyMap(), url))
        }
    }

    private lateinit var fake: FakeTransport
    private lateinit var store: LichessTokenStore

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
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun playingVm(): OnlineChessViewModel {
        val vm = OnlineChessViewModel(app)
        vm.api = LichessApi(store, fake)
        await("signed in") { vm.uiState.value.auth is OnlineAuth.SignedIn }
        fake.eventStream.push(
            """{"type":"gameStart","game":{"gameId":"g1","color":"white","opponent":{"username":"Opp"}}}"""
        )
        await("playing") { vm.uiState.value.connection is OnlineConnection.Playing }
        fake.gameStream.push(
            """{"type":"gameFull","id":"g1","white":{"id":"me-id","name":"Me"},"black":{"id":"op-id","name":"Opp"},"state":{"type":"gameState","moves":"","wtime":600000,"btime":600000,"status":"started"}}"""
        )
        await("full applied") {
            (vm.uiState.value.connection as? OnlineConnection.Playing)?.whiteClockMs == 600000L
        }
        return vm
    }

    private fun playing(vm: OnlineChessViewModel) =
        vm.uiState.value.connection as OnlineConnection.Playing

    @Test fun gameFlowAppliesServerMovesAndSendsLocalMove() {
        val vm = playingVm()
        assertEquals("Opp", playing(vm).opponentName)
        assertEquals(PieceColor.WHITE, playing(vm).localColor)

        fake.gameStream.push(
            """{"type":"gameState","moves":"e2e4 e7e5","wtime":590000,"btime":580000,"status":"started"}"""
        )
        await("moves applied") { playing(vm).turn == PieceColor.WHITE && playing(vm).board.moves.size == 2 }
        assertEquals(580000L, playing(vm).blackClockMs)

        // Select d2 pawn and play d2d4: POSTed, selection cleared, board waits for the echo.
        vm.onSquareClick(Position(6, 3))
        assertEquals(Position(6, 3), playing(vm).selectedPiece)
        vm.onSquareClick(Position(4, 3))
        await("move posted") { fake.requests.any { it.contains("/api/board/game/g1/move/d2d4") } }
        await("selection cleared") { playing(vm).selectedPiece == null }
        assertEquals(2, playing(vm).board.moves.size, "board only advances on server echo")

        // Opponent turn: clicks are ignored.
        fake.gameStream.push(
            """{"type":"gameState","moves":"e2e4 e7e5 d2d4","wtime":570000,"btime":580000,"status":"started"}"""
        )
        await("echo applied") { playing(vm).board.moves.size == 3 }
        vm.onSquareClick(Position(6, 4))
        assertNull(playing(vm).selectedPiece, "with 3 half-moves applied it is Black's turn, so clicks are ignored")

        // A malformed UCI token in the tail is skipped, never applied partially.
        fake.gameStream.push(
            """{"type":"gameState","moves":"e2e4 e7e5 d2d4 e2e","wtime":560000,"btime":580000,"status":"started"}"""
        )
        await("junk token processed") { playing(vm).whiteClockMs == 560000L }
        assertEquals(3, playing(vm).board.moves.size)
    }

    @Test fun terminalStateShowsResultAndResignPosts() {
        val vm = playingVm()
        vm.onResignConfirm()
        await("resign posted") { fake.requests.any { it.contains("/api/board/game/g1/resign") } }

        fake.gameStream.push(
            """{"type":"gameState","moves":"","wtime":0,"btime":0,"status":"resign","winner":"white"}"""
        )
        await("result shown") { playing(vm).resultText != null }
        assertEquals(
            app.getString(com.vayunmathur.games.chess.R.string.online_result_opponent_resigned),
            playing(vm).resultText,
            "winner is my color, so the opponent resigned",
        )
        // I resigned, so the winner is Black: the text must say so (the two resign
        // strings were swapped before the pass-3 fix).
        fake.gameStream.push(
            """{"type":"gameState","moves":"","wtime":0,"btime":0,"status":"resign","winner":"black"}"""
        )
        await("resigned result") {
            playing(vm).resultText ==
                app.getString(com.vayunmathur.games.chess.R.string.online_result_resigned)
        }
        // Clicks are dead once a result is in, and backToLobby returns to Idle.
        vm.onSquareClick(Position(6, 4))
        assertNull(playing(vm).selectedPiece)
        vm.backToLobby()
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
        vm.signOut()
        assertEquals(OnlineAuth.SignedOut, vm.uiState.value.auth)
    }

    @Test fun incomingChallengeIsDeclinedAndAbortPosts() {
        val vm = playingVm()
        fake.eventStream.push("""{"type":"challenge","challenge":{"id":"c77","challenger":{"username":"carol"}}}""")
        await("decline posted") { fake.requests.any { it.contains("/api/challenge/c77/decline") } }
        vm.onAbortConfirm()
        await("abort posted") { fake.requests.any { it.contains("/api/board/game/g1/abort") } }
        vm.signOut()
    }

    @Test fun seekFailureIdlesAndAuthExpiryForcesReauth() {
        val vm = OnlineChessViewModel(app)
        vm.api = LichessApi(store, fake)
        await("signed in") { vm.uiState.value.auth is OnlineAuth.SignedIn }

        fake.handler = { throw IOException("offline") }
        vm.selectTimeControl(LichessTimeControl.RAPID_10_0)
        assertEquals(LichessTimeControl.RAPID_10_0, vm.uiState.value.selectedTimeControl)
        vm.quickSeek()
        await("seek failed") { vm.uiState.value.lobbyError == OnlineLobby.Error.SeekFailed }
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
        vm.dismissLobbyMessage()
        assertNull(vm.uiState.value.lobbyError)

        fake.handler = { SimpleResponse(401, "", "", emptyMap(), it) }
        vm.quickSeek()
        await("reauth") { vm.uiState.value.auth is OnlineAuth.SignedOut }
        assertEquals(OnlineAuth.Error.Expired, vm.uiState.value.authError)
        assertNull(runBlocking { store.tokenNow() }, "expired token is cleared")
        assertEquals(
            app.getString(com.vayunmathur.games.chess.R.string.online_auth_expired),
            vm.authErrorText(OnlineAuth.Error.Expired),
        )
    }

    @Test fun challengeSuccessNoticesAndDeclinedEventIdles() {
        val vm = OnlineChessViewModel(app)
        vm.api = LichessApi(store, fake)
        await("signed in") { vm.uiState.value.auth is OnlineAuth.SignedIn }
        fake.handler = { SimpleResponse(200, "OK", """{"id":"c1"}""", emptyMap(), it) }
        vm.onChallengeUsernameChange("  rival  ")
        vm.challenge()
        await("challenge sent") { vm.uiState.value.lobbyNotice == OnlineLobby.Notice.ChallengeSent("rival") }
        assertTrue(vm.uiState.value.connection is OnlineConnection.Challenging)

        fake.eventStream.push("""{"type":"challengeDeclined","challenge":{"id":"c1"}}""")
        await("declined") { vm.uiState.value.connection is OnlineConnection.Idle }
        assertEquals(OnlineLobby.Notice.ChallengeDeclined, vm.uiState.value.lobbyNotice)
        vm.dismissLobbyMessage()
        assertNull(vm.uiState.value.lobbyNotice)
        vm.signOut()
    }

    @Test fun challengeFailureShowsError() {
        val vm = OnlineChessViewModel(app)
        vm.api = LichessApi(store, fake)
        await("signed in") { vm.uiState.value.auth is OnlineAuth.SignedIn }
        fake.handler = { throw IOException("nope") }
        vm.onChallengeUsernameChange("rival")
        vm.challenge()
        await("challenge failed") { vm.uiState.value.lobbyError == OnlineLobby.Error.ChallengeFailed }
        assertEquals(OnlineConnection.Idle, vm.uiState.value.connection)
        assertNotNull(vm.uiState.value.connection)
        vm.signOut()
    }
}
