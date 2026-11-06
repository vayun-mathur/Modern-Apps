package com.vayunmathur.communicate.googlevoice

import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice-4 coverage for the remaining Google Voice auth surface ([GoogleVoiceAuth]).
 *
 * Pure JVM: pins the full header set (every header the RPCs require) and the
 * hash construction, so a dropped header is caught without a live session.
 */
class GoogleVoiceAuthSlice4Test {

    @Test
    fun sapisidHash_signsTimestampSapisidAndOrigin() {
        val expected = "1700000000_" + GoogleVoiceAuth.sha1Hex("1700000000 sid9 https://example.com")
        assertEquals(expected, GoogleVoiceAuth.sapisidHash(1700000000L, "sid9", "https://example.com"))
    }

    @Test
    fun authorization_prefixesTheHash() {
        assertEquals(
            "SAPISIDHASH " + GoogleVoiceAuth.sapisidHash(42L, "s"),
            GoogleVoiceAuth.authorization(42L, "s"),
        )
    }

    @Test
    fun headers_coverEveryRequiredField() {
        val headers = GoogleVoiceAuth.headers(
            cookieHeader = "SAPISID=s; A=1",
            sapisid = "s",
            authUser = "3",
            nowSeconds = 100L,
        )
        assertEquals("SAPISID=s; A=1", headers["Cookie"])
        assertEquals("3", headers["X-Goog-AuthUser"])
        assertEquals("application/json+protobuf", headers["Content-Type"])
        assertEquals("https://voice.google.com", headers["Origin"])
        assertEquals("https://voice.google.com/", headers["Referer"])
        assertEquals("XMLHttpRequest", headers["X-Requested-With"])
        assertTrue(headers["User-Agent"]!!.contains("Chrome/"), headers["User-Agent"])
        assertEquals("SAPISIDHASH " + GoogleVoiceAuth.sapisidHash(100L, "s"), headers["Authorization"])
        assertEquals(GoogleVoiceAuth.ORIGIN, "https://voice.google.com")
        assertEquals(GoogleVoiceAuth.CONTENT_TYPE, "application/json+protobuf")
    }
}
