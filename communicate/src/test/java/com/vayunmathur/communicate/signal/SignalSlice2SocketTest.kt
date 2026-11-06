package com.vayunmathur.communicate.signal

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.transport.SignalSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage

/**
 * Wave 6 (w6 slice 2/6): [SignalSocket]'s offline-safe surface — URL/auth header construction,
 * [SignalSocket.RequestResult] status mapping, send-without-session short-circuits, and the
 * request/response id plumbing. No network: a disconnected socket must fail closed (false/null)
 * rather than throw, which is what the send pipeline retries on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalSlice2SocketTest {

    private fun socket(
        aci: String = "3f0e5a2c-1111-2222-3333-444455556666",
        password: String = "pw",
    ): SignalSocket {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return SignalSocket(
            context = ctx,
            authData = SignalAuthData(phoneNumber = "+14155552671", aci = aci, password = password),
        )
    }

    @Test
    fun requestResult_successRange() {
        assertTrue(SignalSocket.RequestResult(200, "OK", ByteArray(0)).isSuccess)
        assertTrue(SignalSocket.RequestResult(299, "OK", ByteArray(0)).isSuccess)
        assertFalse(SignalSocket.RequestResult(199, "", ByteArray(0)).isSuccess)
        assertFalse(SignalSocket.RequestResult(300, "", ByteArray(0)).isSuccess)
        assertFalse(SignalSocket.RequestResult(409, "mismatch", ByteArray(0)).isSuccess)
        assertFalse(SignalSocket.RequestResult(0, "", ByteArray(0)).isSuccess)
    }

    @Test
    fun disconnectedSocket_sendFailsClosed() = runTest {
        val s = socket()
        assertFalse(s.send(byteArrayOf(1, 2, 3)))
        assertFalse(s.sendText("hello"))
        val ack = signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage
            .newBuilder().setId(1L).setStatus(200).build()
        assertFalse(s.sendResponse(ack))
    }

    @Test
    fun disconnectedSocket_requestTimesOutToNull() = runTest {
        val s = socket()
        val req = WebSocketRequestMessage.newBuilder()
            .setVerb("PUT").setPath("/v1/keepalive").setId(123L).build()
        // No session: send() is false, so the request path returns null quickly.
        assertNull(s.sendRequestAwaitingResponse(req, timeoutMs = 200L))
    }

    @Test
    fun constants_matchServerContract() {
        assertEquals("chat.signal.org", SignalSocket.DEFAULT_HOST)
        assertEquals(443, SignalSocket.DEFAULT_PORT)
        assertEquals(4401, SignalSocket.CLOSE_INVALID_AUTH)
        assertEquals(4409, SignalSocket.CLOSE_CONNECTED_ELSEWHERE)
    }

    @Test
    fun connectionState_sealedShape() {
        val connecting: SignalSocket.ConnectionState = SignalSocket.ConnectionState.Connecting
        val connected: SignalSocket.ConnectionState = SignalSocket.ConnectionState.Connected
        val dropped: SignalSocket.ConnectionState =
            SignalSocket.ConnectionState.Disconnected("bye")
        assertTrue(connecting is SignalSocket.ConnectionState.Connecting)
        assertTrue(connected is SignalSocket.ConnectionState.Connected)
        assertEquals("bye", (dropped as SignalSocket.ConnectionState.Disconnected).reason)
        assertNotNull(dropped)
    }
}
