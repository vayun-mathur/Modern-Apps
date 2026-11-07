package com.vayunmathur.appstore.data

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * FDroidRepository's file download against a loopback server: bytes land
 * on disk verbatim, and an HTTP error raises instead of writing a
 * truncated index that would fail its hash check with a worse message.
 */
@RunWith(RobolectricTestRunner::class)
class FDroidDownloadTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var outDir: File

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/index") { exchange ->
            val bytes = ByteArray(70_000) { (it % 251).toByte() }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/broken") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        outDir = File.createTempFile("fdroid-dl", "dir").let { it.delete(); it.mkdirs(); it }
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        outDir.deleteRecursively()
    }

    @Test
    fun `download writes the served bytes verbatim`() {
        val out = File(outDir, "index-v2.json")
        FDroidRepository.downloadToFile("$base/index", out)
        assertTrue(out.exists())
        assertEquals(70_000L, out.length())
        assertEquals(0.toByte(), out.readBytes()[0])
        assertEquals((69_999 % 251).toByte(), out.readBytes()[69_999])
    }

    @Test
    fun `an http error raises an IOException`() {
        val out = File(outDir, "nope.json")
        val e = assertFailsWith<IOException> {
            FDroidRepository.downloadToFile("$base/broken", out)
        }
        assertTrue(e.message!!.contains("500"), e.message!!)
    }
}
