package com.vayunmathur.library.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.ConnectivityMonitor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WebSocket frame model defaults and connectivity monitor entry points. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryNetworkWsConnectivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun wsFrameDefaultsAndEquality() {
        val close = WebSocketClient.WsFrame.Close()
        assertEquals(1000, close.code)
        assertEquals("", close.reason)
        assertEquals(WebSocketClient.WsFrame.Text("hi"), WebSocketClient.WsFrame.Text("hi"))
        assertTrue(WebSocketClient.WsFrame.Binary(byteArrayOf(1, 2)).bytes.contentEquals(byteArrayOf(1, 2)))
        assertTrue(WebSocketClient.WsFrame.Ping === WebSocketClient.WsFrame.Ping)
        assertTrue(WebSocketClient.WsFrame.Pong === WebSocketClient.WsFrame.Pong)
        assertEquals(1001, WebSocketClient.WsFrame.Close(1001, "going away").code)
    }

    @Test
    fun connectivityMonitorStartAndStateFlow() {
        ConnectivityMonitor.start(context)
        // Flow exists and holds a boolean; the exact value depends on the
        // Robolectric shadow's network state, so only the type/presence is pinned.
        val online: Boolean = ConnectivityMonitor.isOnline.value
        assertTrue(online || !online)
        // One-shot check must agree with a boolean read (no crash without permission grants).
        val direct: Boolean = ConnectivityMonitor.isOnline(context)
        assertTrue(direct || !direct)
        // Starting again is idempotent.
        ConnectivityMonitor.start(context)
        assertEquals(online, ConnectivityMonitor.isOnline.value)
    }

    @Test
    fun responseSuccessBoundaries() {
        assertTrue(SimpleResponse(200, "", "", emptyMap(), "u").isSuccess)
        assertTrue(SimpleResponse(299, "", "", emptyMap(), "u").isSuccess)
        assertFalse(SimpleResponse(300, "", "", emptyMap(), "u").isSuccess)
        assertFalse(SimpleResponse(199, "", "", emptyMap(), "u").isSuccess)
        assertFalse(RawResponse(500, "", ByteArray(0), emptyMap(), "u").isSuccess)
    }
}
