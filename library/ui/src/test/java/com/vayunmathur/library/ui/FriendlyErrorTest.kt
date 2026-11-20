package com.vayunmathur.library.ui

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.ParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** friendlyErrorRes: every common shape maps, and raw text never comes back. */
class FriendlyErrorTest {
    @Test fun nullIsGeneric() {
        assertEquals(R.string.friendly_error_generic, friendlyErrorRes(null as Throwable?))
        assertEquals(R.string.friendly_error_generic, friendlyErrorRes(null as String?))
    }

    @Test fun offlineAndIo() {
        assertEquals(R.string.friendly_error_offline, friendlyErrorRes(UnknownHostException("api.secret.example")))
        assertEquals(R.string.friendly_error_offline, friendlyErrorRes(IOException("socket closed to 10.0.0.1")))
    }

    @Test fun timeoutBeatsIo() {
        // SocketTimeoutException is an IOException: timeout must win.
        assertEquals(R.string.friendly_error_timeout, friendlyErrorRes(SocketTimeoutException("read timed out")))
    }

    @Test fun httpStatusFromMessage() {
        assertEquals(R.string.friendly_error_http_server, friendlyErrorRes(IOException("HTTP 500 for https://secret.example/x")))
        assertEquals(R.string.friendly_error_http_not_found, friendlyErrorRes(IOException("HTTP 404 for https://x")))
        assertEquals(R.string.friendly_error_http_auth, friendlyErrorRes(IOException("HTTP 403 for https://x")))
        assertEquals(R.string.friendly_error_http_rate_limited, friendlyErrorRes(IOException("HTTP 429 for https://x")))
        assertEquals(R.string.friendly_error_http_code, friendlyErrorRes(IOException("HTTP 400 for https://x")))
    }

    @Test fun parse() {
        assertEquals(R.string.friendly_error_parse, friendlyErrorRes(ParseException("bad offset 42", 42)))
    }

    @Test fun causeChainIsWalked() {
        val wrapped = RuntimeException("outer secret", UnknownHostException("inner secret"))
        assertEquals(R.string.friendly_error_offline, friendlyErrorRes(wrapped))
    }

    @Test fun stringOverloadClassifiesButNeverEchoes() {
        assertEquals(R.string.friendly_error_timeout, friendlyErrorRes("java.net.SocketTimeoutException: timed out after 30s"))
        assertEquals(R.string.friendly_error_parse, friendlyErrorRes("malformed JSON at column 9"))
        assertEquals(R.string.friendly_error_generic, friendlyErrorRes("NullPointerException at SecretClass.kt:12"))
        // The generic result is a resource id, by construction not the input.
        assertNotEquals(R.string.friendly_error_offline, friendlyErrorRes("totally unknown failure"))
    }

    @Test fun unknownIsGeneric() {
        assertEquals(R.string.friendly_error_generic, friendlyErrorRes(IllegalStateException("secret internals")))
    }
}
