package com.vayunmathur.backup.data.backend

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WebDavBackendTest {
    private lateinit var server: HttpServer
    private val files = mutableMapOf<String, ByteArray>()
    private val dirs = mutableSetOf<String>()
    private var base = ""
    @Volatile var failNextPut = false
    @Volatile var failMkcol = false

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}/dav"
        // Seed root dir so PROPFIND on base paths works: our handler treats path prefix "dav"
        dirs.add("dav")
    }

    private fun handle(ex: com.sun.net.httpserver.HttpExchange) {
        val path = URLDecoder.decode(ex.requestURI.path, "UTF-8").trim('/')
        val auth = ex.requestHeaders.getFirst("Authorization")
        if (auth == null || !auth.startsWith("Basic ")) {
            val b = "no auth".toByteArray(); ex.sendResponseHeaders(401, b.size.toLong()); ex.responseBody.use { it.write(b) }; ex.close(); return
        }
        when (ex.requestMethod) {
            "MKCOL" -> { if (failMkcol) ex.sendResponseHeaders(500, -1) else { dirs.add(path); ex.sendResponseHeaders(201, -1) } }
            "PUT" -> handlePut(ex, path)
            "GET" -> handleGet(ex, path)
            "DELETE" -> {
                if (files.remove(path) != null || dirs.remove(path)) ex.sendResponseHeaders(204, -1) else ex.sendResponseHeaders(404, -1)
            }
            "PROPFIND" -> handlePropfind(ex, path)
            else -> ex.sendResponseHeaders(405, -1)
        }
        ex.close()
    }

    private fun handlePut(ex: com.sun.net.httpserver.HttpExchange, path: String) {
        if (failNextPut) { failNextPut = false; ex.sendResponseHeaders(500, -1) }
        else { files[path] = ex.requestBody.readBytes(); ex.sendResponseHeaders(201, -1) }
    }

    private fun handleGet(ex: com.sun.net.httpserver.HttpExchange, path: String) {
        val data = files[path]
        if (data == null) ex.sendResponseHeaders(404, -1)
        else { ex.sendResponseHeaders(200, data.size.toLong()); ex.responseBody.use { it.write(data) } }
    }

    private fun handlePropfind(ex: com.sun.net.httpserver.HttpExchange, path: String) {
        val depth = ex.requestHeaders.getFirst("Depth") ?: "1"
        ex.requestBody.readBytes()
        val exists = path.isEmpty() || path in dirs || path in files || files.keys.any { it.startsWith("$path/") }
        if (!exists) { ex.sendResponseHeaders(404, -1); return }
        val hrefs = mutableListOf("/$path")
        if (depth == "1") hrefs.addAll(childHrefs(path))
        val xml = buildString {
            append("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">""")
            hrefs.forEach { append("<d:response><d:href>").append(it).append("</d:href></d:response>") }
            append("</d:multistatus>")
        }.toByteArray()
        ex.responseHeaders.set("Content-Type", "application/xml")
        ex.sendResponseHeaders(207, xml.size.toLong()); ex.responseBody.use { it.write(xml) }
    }

    private fun childHrefs(path: String): List<String> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val children = sortedSetOf<String>()
        (files.keys + dirs).forEach { k ->
            if (k.startsWith(prefix) && k != path) {
                val rest = k.removePrefix(prefix); if (rest.isNotEmpty()) children.add("/$prefix" + rest.substringBefore('/'))
            }
        }
        return children.toList()
    }

    @After fun tearDown() { server.stop(0) }

    private fun backend() = WebDavBackend("$base/", "user", "pass")

    @Test fun crudRoundTrip() = runBlocking<Unit> {
        val b = backend()
        assertEquals("127.0.0.1", b.displayName)
        b.ensureDir("kv/com.a")
        b.ensureDir("kv/com.a") // tolerated exists
        b.save("kv/com.a/k1", "hello".toByteArray())
        assertTrue(b.exists("kv/com.a/k1"))
        assertContentEquals("hello".toByteArray(), b.load("kv/com.a/k1"))
        b.save("kv/com.a/k2", "x".toByteArray())
        assertEquals(listOf("k1", "k2"), b.list("kv/com.a").sorted())
        assertEquals(emptyList(), b.list("nope/missing").also { })
        b.delete("kv/com.a/k1")
        assertFalse(b.exists("kv/com.a/k1"))
        b.delete("kv/com.a/k1") // 404 tolerated
        assertFailsWith<FileNotFoundException> { b.load("kv/com.a/k1") }
        assertEquals("custom", WebDavBackend(base, "u", "p", displayName = "custom").displayName)
    }

    @Test fun writeAutoCreatesParentsAndErrors() = runBlocking<Unit> {
        val b = backend()
        b.save("deep/nested/dir/file.bin", byteArrayOf(5, 6))
        assertContentEquals(byteArrayOf(5, 6), b.load("deep/nested/dir/file.bin"))
        failNextPut = true
        assertFailsWith<IOException> { b.save("bad.bin", "x".toByteArray()) }
        // list on existing dir includes nested leaf names only
        assertTrue("file.bin" in b.list("deep/nested/dir"))
    }

    @Test fun ensureDirSurfacesServerErrors() = runBlocking<Unit> {
        failMkcol = true
        assertFailsWith<IOException> { backend().ensureDir("kv/com.a") }
    }

    @Test fun pathsWithSpacesAreEncoded() = runBlocking<Unit> {
        val b = backend()
        b.save("my dir/my file.txt", "sp".toByteArray())
        assertEquals("sp", b.load("my dir/my file.txt").decodeToString())
        assertTrue(b.exists("my dir/my file.txt"))
    }
}
