package com.vayunmathur.globalesim.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ApiErrorTest {
    @Test
    fun `parses HTTP status from NetworkClient message`() {
        val out = EsimApi.parseApiError(java.io.IOException("HTTP 404: unknown account"))
        assertIs<EsimApi.ApiError>(out)
        assertEquals(404, out.statusCode)
    }

    @Test
    fun `parses other status codes`() {
        val out = EsimApi.parseApiError(java.io.IOException("HTTP 429: upstream busy, retry soon"))
        assertIs<EsimApi.ApiError>(out)
        assertEquals(429, out.statusCode)
    }

    @Test
    fun `non-HTTP IOException passes through untouched`() {
        val original = java.io.IOException("Unable to resolve host")
        assertSame(original, EsimApi.parseApiError(original))
    }

    @Test
    fun `ApiError passes through untouched`() {
        val original = EsimApi.ApiError(404, "HTTP 404: x")
        assertSame(original, EsimApi.parseApiError(original))
    }
}
