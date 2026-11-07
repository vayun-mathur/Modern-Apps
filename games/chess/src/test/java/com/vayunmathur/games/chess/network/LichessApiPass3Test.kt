package com.vayunmathur.games.chess.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.chess.auth.LichessTokenStore
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.SimpleResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pass 3: LichessApi against a scripted fake transport (never the real network). */
@RunWith(RobolectricTestRunner::class)
class LichessApiPass3Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private class ChunkedStream(private val bytes: ByteArray, private val chunk: Int = 3) : NetworkDataStream {
        private var pos = 0
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(chunk, length, bytes.size - pos)
            bytes.copyInto(buffer, offset, pos, pos + n)
            pos += n
            return n
        }
        override val isClosedForRead: Boolean get() = pos >= bytes.size
    }

    private class FakeTransport : LichessTransport {
        data class Call(val url: String, val method: String, val headers: Map<String, String>, val body: String?)
        val calls = mutableListOf<Call>()
        var handler: (Call) -> SimpleResponse = { SimpleResponse(200, "OK", "", emptyMap(), it.url) }
        var accountResult: LichessProfile = LichessProfile("me-id", "meuser")
        var accountError: Exception? = null
        var eventBytes: ByteArray = ByteArray(0)
        var gameBytes: (String) -> ByteArray = { ByteArray(0) }
        var streamStatus: Int = 200

        override suspend fun request(url: String, method: String, headers: Map<String, String>, body: String?): SimpleResponse {
            val call = Call(url, method, headers, body)
            calls += call
            return handler(call)
        }

        override suspend fun account(url: String, headers: Map<String, String>): LichessProfile {
            calls += Call(url, "GET", headers, null)
            accountError?.let { throw it }
            return accountResult
        }

        override suspend fun stream(
            url: String,
            headers: Map<String, String>,
            readTimeoutMs: Long,
            block: suspend (NetworkDataStream?, SimpleResponse) -> Unit,
        ) {
            calls += Call(url, "GET", headers, null)
            if (streamStatus !in 200..299) {
                block(null, SimpleResponse(streamStatus, "", "err-body", emptyMap(), url))
                return
            }
            val bytes = if (url.contains("/stream/event")) eventBytes else gameBytes(url)
            block(ChunkedStream(bytes), SimpleResponse(200, "OK", "", emptyMap(), url))
        }
    }

    private lateinit var fake: FakeTransport
    private lateinit var api: LichessApi

    @BeforeTest fun setUp() = runBlocking {
        val store = LichessTokenStore(app)
        store.clear()
        store.saveToken("secret-token")
        store.saveUsername("meuser")
        store.saveUserId("me-id")
        fake = FakeTransport()
        api = LichessApi(store, fake)
    }

    private fun resp(status: Int, body: String = ""): (FakeTransport.Call) -> SimpleResponse =
        { SimpleResponse(status, "", body, emptyMap(), it.url) }

    @Test fun accountReturnsProfileWithAuthHeader() = runBlocking {
        val profile = api.account()
        assertEquals(LichessProfile("me-id", "meuser"), profile)
        assertEquals("Bearer secret-token", fake.calls.single().headers["Authorization"])
        assertTrue(fake.calls.single().url.endsWith("/api/account"))
    }

    @Test fun account401MapsToAuthExpiredAndOtherErrorsPropagate() = runBlocking {
        fake.accountError = IOException("HTTP 401 Unauthorized")
        assertFailsWith<LichessAuthExpired> { api.account() }
        fake.accountError = IOException("socket closed")
        val e = assertFailsWith<IOException> { api.account() }
        assertEquals("socket closed", e.message)
    }

    @Test fun seekPostsFormAndChecksStatus() = runBlocking {
        api.seek(LichessTimeControl.RAPID_5_3)
        val call = fake.calls.single()
        assertEquals("POST", call.method)
        assertTrue(call.url.endsWith("/api/board/seek"), call.url)
        assertTrue(call.body!!.contains("rated=false"), call.body)
        assertTrue(call.body.contains("time=5"), call.body)
        assertTrue(call.body.contains("increment=3"), call.body)

        fake.handler = resp(401)
        assertFailsWith<LichessAuthExpired> { api.seek(LichessTimeControl.BLITZ_3_2) }
        fake.handler = resp(500, "server boom")
        val e = assertFailsWith<IOException> { api.seek(LichessTimeControl.BULLET) }
        assertTrue(e.message!!.contains("HTTP 500"), e.message)
        assertTrue(e.message!!.contains("server boom"), e.message)
    }

    @Test fun challengeParsesIdEncodesUserAndToleratesGarbage() { runBlocking {
        fake.handler = resp(200, """{"id":"chal-1"}""")
        assertEquals("chal-1", api.challenge("some user", LichessTimeControl.BLITZ_3_2))
        assertTrue(fake.calls.last().url.contains("some+user"), fake.calls.last().url)
        assertTrue(fake.calls.last().body!!.contains("clock.limit=180"), fake.calls.last().body)

        fake.handler = resp(200, "not json")
        assertEquals("", api.challenge("bob", LichessTimeControl.BULLET))
        fake.handler = resp(403, "nope")
        assertFailsWith<IOException> { api.challenge("bob", LichessTimeControl.BULLET) }
    } }

    @Test fun moveResignAbortDeclineHitBoardEndpoints() { runBlocking {
        api.sendMove("g1", "e2e4")
        api.resign("g1")
        api.abort("g1")
        api.declineChallenge("c9")
        val urls = fake.calls.map { it.url }
        assertTrue(urls[0].endsWith("/api/board/game/g1/move/e2e4"), urls.toString())
        assertTrue(urls[1].endsWith("/api/board/game/g1/resign"), urls.toString())
        assertTrue(urls[2].endsWith("/api/board/game/g1/abort"), urls.toString())
        assertTrue(urls[3].endsWith("/api/challenge/c9/decline"), urls.toString())

        fake.handler = resp(401)
        assertFailsWith<LichessAuthExpired> { api.sendMove("g1", "e2e4") }
        assertFailsWith<LichessAuthExpired> { api.resign("g1") }
    } }

    @Test fun streamEventsDecodesNdjsonAcrossChunks() = runBlocking {
        fake.eventBytes = listOf(
            """{"type":"gameStart","game":{"gameId":"g1","color":"black","opponent":{"username":"opp"}}}""",
            "",
            "garbage line",
            """{"type":"challenge","challenge":{"id":"c1","challenger":{"username":"carol"}}}""",
            """{"type":"challengeDeclined","challenge":{"id":"c2"}}""",
            """{"type":"gameFinish","game":{"gameId":"g1"}}""",
            """{"type":"newServerThing","game":{"gameId":"zz"}}""",
        ).joinToString("\n").toByteArray()
        val events = mutableListOf<LichessEvent>()
        api.streamEvents { events += it }
        assertEquals(
            listOf(
                LichessEvent.GameStart("g1", PieceColor.BLACK, "opp"),
                LichessEvent.Challenge("c1", "carol"),
                LichessEvent.ChallengeDeclined("c2"),
                LichessEvent.GameFinish("g1"),
            ),
            events,
        )
    }

    @Test fun streamGameDecodesFullThenState() = runBlocking {
        fake.gameBytes = {
            listOf(
                """{"type":"gameFull","id":"g1","white":{"id":"me-id","name":"Me"},"black":{"id":"op","name":"Opp"},"state":{"type":"gameState","moves":"","wtime":60000,"btime":59000,"status":"started"}}""",
                """{"type":"gameState","moves":"e2e4 e7e5","wtime":55000,"btime":57000,"status":"started"}""",
                """{"type":"chatLine","text":"hi"}""",
                "junk",
            ).joinToString("\n").toByteArray()
        }
        val updates = mutableListOf<LichessGameUpdate>()
        api.streamGame("g1") { updates += it }
        assertEquals(2, updates.size)
        val full = updates[0] as LichessGameUpdate.Full
        assertEquals("g1", full.game.id)
        assertEquals(PieceColor.WHITE, full.game.myColor("me-id"))
        val state = updates[1] as LichessGameUpdate.State
        assertEquals("e2e4 e7e5", state.state.moves)
        assertEquals(57000, state.state.btime)
    }

    @Test fun streamHttpErrorsThrowAndEmptyOkStreamReturns() = runBlocking {
        fake.streamStatus = 401
        assertFailsWith<LichessAuthExpired> { api.streamEvents {} }
        assertFailsWith<LichessAuthExpired> { api.streamGame("g1") {} }
        fake.streamStatus = 500
        assertFailsWith<IOException> { api.streamEvents {} }
        fake.streamStatus = 200
        fake.eventBytes = ByteArray(0)
        val events = mutableListOf<LichessEvent>()
        api.streamEvents { events += it }
        assertTrue(events.isEmpty())
    }
}
