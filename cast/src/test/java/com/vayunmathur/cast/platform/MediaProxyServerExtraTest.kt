package com.vayunmathur.cast.platform

import com.vayunmathur.cast.protocol.MediaResource
import java.io.InputStream
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaProxyServerExtraTest {

    private val emptyResolver = com.vayunmathur.cast.protocol.MediaResourceResolver { null }

    @Test
    fun `random token is url safe unpadded and unique`() {
        val a = MediaProxyServer.randomToken()
        val b = MediaProxyServer.randomToken()
        assertNotEquals(a, b)
        assertEquals(43, a.length) // 32 bytes, base64url without padding
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' }, a)
        assertFalse(a.contains('='))
    }

    @Test
    fun `endpoint equality is by port and fingerprint content`() {
        val fp = byteArrayOf(1, 2, 3)
        val a = MediaProxyServer.Endpoint(8080, fp)
        val b = MediaProxyServer.Endpoint(8080, fp.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, MediaProxyServer.Endpoint(8081, fp))
        assertNotEquals(a, MediaProxyServer.Endpoint(8080, byteArrayOf(9)))
    }

    @Test
    fun `local addresses returns a list without throwing`() {
        val addresses = MediaProxyServer.localAddresses()
        assertTrue(addresses.all { !it.isLoopbackAddress })
    }

    @Test
    fun `start binds, second start refused, stop is idempotent`() {
        val server = MediaProxyServer(MediaProxyServer.randomToken(), emptyResolver)
        val endpoint = assertNotNull(server.start(listOf(InetAddress.getLoopbackAddress())))
        assertTrue(endpoint.port > 0)
        assertTrue(endpoint.certificateFingerprint.isNotEmpty())
        assertNull(server.start(listOf(InetAddress.getLoopbackAddress())), "single-use start")
        assertEquals(0, server.openConnections)
        server.stop()
        server.stop()
    }

    @Test
    fun `stop before start is safe`() {
        MediaProxyServer("token", emptyResolver).stop()
    }
}
