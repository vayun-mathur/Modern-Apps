package com.vayunmathur.maps.util
import com.vayunmathur.maps.data.OfflineRouterTraffic

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 traffic tile server: the loopback HTTP surface answers 204 when the
 * native graph has no tile, and drops malformed request lines without a
 * response. The server is started once per process, so the port is read
 * back out of the object.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsTrafficServerC5Test {

    private fun port(): Int {
        val field = OfflineRouterTraffic::class.java.getDeclaredField("serverPort")
        field.isAccessible = true
        return field.getInt(OfflineRouterTraffic)
    }

    @BeforeTest
    fun startServer() {
        if (port() == 0) {
            OfflineRouterTraffic.startLocalTileServer()
            // The listener binds on its own thread.
            repeat(100) {
                if (port() != 0) return
                Thread.sleep(10)
            }
        }
        assertTrue(port() != 0, "tile server did not start")
    }

    /** Status line plus headers of one request (body is not read). */
    private fun request(line: String): Pair<String, Map<String, String>> {
        Socket(InetAddress.getLoopbackAddress(), port()).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("$line\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val status = reader.readLine() ?: return "" to emptyMap()
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val h = reader.readLine()
                if (h.isNullOrEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim()] = h.substring(i + 1).trim()
            }
            return status to headers
        }
    }

    @Test
    fun `tile request answers with the tile content type`() {
        // The native graph answers (possibly with an empty tile), so the
        // server takes the 200 path with the vector-tile headers.
        val (status, headers) = request("GET /traffic/14/2620/6331 HTTP/1.1")
        assertEquals("HTTP/1.1 200 OK", status)
        assertEquals("application/vnd.mapbox-vector-tile", headers["Content-Type"])
        assertEquals("gzip", headers["Content-Encoding"])
        assertTrue(headers["Content-Length"]!!.toInt() >= 0)
    }

    @Test
    fun `query strings and bad coordinates still parse`() {
        assertEquals(
            "HTTP/1.1 200 OK",
            request("GET /traffic/14/2620/6331?ts=1 HTTP/1.1").first,
        )
        assertEquals(
            "HTTP/1.1 200 OK",
            request("GET /traffic/x/y/z HTTP/1.1").first,
        )
    }

    @Test
    fun `malformed requests get no response`() {
        assertEquals("", request("POST /traffic/14/2620/6331 HTTP/1.1").first)
        assertEquals("", request("GET /other/14/2620 HTTP/1.1").first)
        assertEquals("", request("GET /traffic/14/2620 HTTP/1.1").first)
        assertEquals("", request("GARBAGE").first)
    }
}
