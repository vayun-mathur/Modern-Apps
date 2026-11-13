package com.vayunmathur.library.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers [StaticMirror]'s static-first / fallback policy through its transport seam. */
class StaticMirrorTest {

    @Serializable
    private data class Dto(val v: Int = 0)

    private val requested = mutableListOf<String>()

    private fun serve(vararg routes: Pair<String, RawResponse>) {
        val map = routes.toMap()
        StaticMirror.transport = { url ->
            requested += url
            map[url] ?: throw java.io.IOException("connect failed")
        }
    }

    private fun resp(status: Int, body: String, lastModified: String? = null) = RawResponse(
        status = status,
        statusMessage = "",
        bytes = body.toByteArray(),
        headers = lastModified?.let { mapOf("last-modified" to listOf(it)) } ?: emptyMap(),
        url = "",
    )

    @AfterTest
    fun reset() {
        StaticMirror.transport = null
    }

    private val now = StaticMirror.parseHttpDate("Fri, 13 Nov 2026 22:00:00 GMT")!!

    @Test
    fun static_copy_wins_when_present() = runBlocking {
        serve("s" to resp(200, """{"v":1}"""), "f" to resp(200, """{"v":2}"""))
        assertEquals(Dto(1), StaticMirror.getJson<Dto>("s", "f"))
        assertEquals(listOf("s"), requested)
    }

    @Test
    fun missing_failing_or_undecodable_static_copy_falls_back() = runBlocking {
        serve("s" to resp(404, "NoSuchKey"), "f" to resp(200, """{"v":2}"""))
        assertEquals(Dto(2), StaticMirror.getJson<Dto>("s", "f"))
        serve("f" to resp(200, """{"v":3}""")) // static host unreachable
        assertEquals(Dto(3), StaticMirror.getJson<Dto>("s", "f"))
        serve("s" to resp(200, "<html>"), "f" to resp(200, """{"v":4}"""))
        assertEquals(Dto(4), StaticMirror.getJson<Dto>("s", "f"))
    }

    @Test
    fun stale_static_copy_falls_back_fresh_one_is_used() = runBlocking {
        val hourAgo = "Fri, 13 Nov 2026 21:00:00 GMT"
        serve("s" to resp(200, "static", hourAgo), "f" to resp(200, "server"))
        assertEquals("static", StaticMirror.get("s", "f", maxAgeMs = 2 * 3_600_000L, nowMs = now).text)
        assertEquals("server", StaticMirror.get("s", "f", maxAgeMs = 600_000L, nowMs = now).text)
        // No Last-Modified at all: cannot prove freshness.
        serve("s" to resp(200, "static"), "f" to resp(200, "server"))
        assertEquals("server", StaticMirror.get("s", "f", maxAgeMs = 600_000L, nowMs = now).text)
        assertEquals("static", StaticMirror.get("s", "f", nowMs = now).text)
    }

    @Test
    fun failing_fallback_throws_like_getJson() {
        serve("s" to resp(404, ""), "f" to resp(503, "down"))
        val e = assertFailsWith<java.io.IOException> { runBlocking { StaticMirror.getJson<Dto>("s", "f") } }
        assertTrue(e.message!!.startsWith("HTTP 503"))
    }

    @Test
    fun get_static_returns_only_usable_copies() = runBlocking {
        serve("s" to resp(200, "[]"), "gone" to resp(404, ""))
        assertEquals("[]", StaticMirror.getStatic("s")?.text)
        assertNull(StaticMirror.getStatic("gone"))
        assertNull(StaticMirror.getStatic("unreachable"))
        assertNull(StaticMirror.getStatic("s", maxAgeMs = 1000L, nowMs = now), "no Last-Modified")
    }

    @Test
    fun http_dates_parse() {
        assertEquals(1794607200000L, now)
        assertNull(StaticMirror.parseHttpDate("yesterday"))
        assertFalse(StaticMirror.accept(resp(500, ""), null, now))
    }
}
