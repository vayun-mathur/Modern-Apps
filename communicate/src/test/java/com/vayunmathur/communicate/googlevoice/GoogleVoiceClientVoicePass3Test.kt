package com.vayunmathur.communicate.googlevoice

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceAuthException
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.GvFolder
import com.vayunmathur.library.network.SimpleResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Voice pass-3: GoogleVoiceClient over a fake transport replaying recorded protojson. */
@RunWith(RobolectricTestRunner::class)
class GoogleVoiceClientVoicePass3Test {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val threadBody = "[[[\"t.+14152124034\",1,[[\"h1\",1786409649630,\"+12133945548\"," +
        "[\"+14152124034\",\"+14152124034\",null,null,null,null,0],10,1,null,null,null," +
        "\"Hello there\",null,null,5,1,null,\"+14152124034\",0]],null,[[\"+14152124034\"]]]]]"

    private class FakeTransport(var status: Int = 200, var body: String = "") {
        val requests = mutableListOf<Triple<String, Map<String, String>, String>>()
        suspend fun post(url: String, headers: Map<String, String>, requestBody: String): SimpleResponse {
            requests += Triple(url, headers, requestBody)
            return SimpleResponse(status, "fake", body, emptyMap(), url)
        }
    }

    private suspend fun signedInSession(): GoogleVoiceSession {
        val session = GoogleVoiceSession.get(ctx)
        session.save(
            cookieHeader = "SAPISID=sapisid-value; OTHER=x",
            apiKey = "test-api-key",
            authUser = "0",
        )
        return session
    }

    @Test
    fun getAccountParsesAndStoresPhoneNumber() = runBlocking {
        val transport = FakeTransport(body = "[\"+15550001111\",null,[\"settings\"]]")
        val client = GoogleVoiceClient(signedInSession()) { u, h, b -> transport.post(u, h, b) }
        val account = client.getAccount()
        assertEquals("+15550001111", account.phoneNumber)
        assertEquals("+15550001111", GoogleVoiceSession.get(ctx).phoneNumber())
        val (url, headers, _) = transport.requests.single()
        assertTrue(url.contains("account/get"), url)
        assertTrue(url.contains("key=test-api-key"), url)
        assertTrue(headers.containsKey("Authorization"))
        assertEquals("SAPISID=sapisid-value; OTHER=x", headers["Cookie"])
        Unit
    }

    @Test
    fun listAndSearchThreadsParseRecordedBody() = runBlocking {
        val transport = FakeTransport(body = threadBody)
        val client = GoogleVoiceClient(signedInSession()) { u, h, b -> transport.post(u, h, b) }
        val threads = client.listThreads(GvFolder.Inbox)
        assertEquals(1, threads.size)
        assertEquals("+14152124034", threads.first().phoneNumber)
        val searched = client.search("hello")
        assertEquals(1, searched.size)
        assertTrue(transport.requests[0].first.contains("api2thread/list"))
        assertTrue(transport.requests[1].first.contains("api2thread/search"))
        assertTrue(transport.requests[1].third.contains("hello"))
        Unit
    }

    @Test
    fun getThreadFindsMessagesAndFallsBackAcrossFolders() = runBlocking {
        val transport = FakeTransport(body = threadBody)
        val client = GoogleVoiceClient(signedInSession()) { u, h, b -> transport.post(u, h, b) }
        val messages = client.getThread("t.+14152124034")
        assertEquals(1, messages.size)
        assertEquals("Hello there", messages.first().text)
        // Unknown thread: Inbox then All are both consulted, result stays empty
        val none = client.getThread("t.missing")
        assertTrue(none.isEmpty())
        assertTrue(transport.requests.size >= 3)
        Unit
    }

    @Test
    fun listCallsAndVoicemailsHitListEndpoint() = runBlocking {
        val transport = FakeTransport(body = threadBody)
        val client = GoogleVoiceClient(signedInSession()) { u, h, b -> transport.post(u, h, b) }
        client.listCalls()
        client.listVoicemails()
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests.all { it.first.contains("api2thread/list") })
        Unit
    }

    @Test
    fun sendsAndUpdatesPostExpectedBodies() = runBlocking {
        val transport = FakeTransport(body = "[]")
        val client = GoogleVoiceClient(signedInSession()) { u, h, b -> transport.post(u, h, b) }
        client.sendSms("+1555", "hi there")
        client.sendPreparedSms("[prepared]")
        client.updateThreadAttributes("t.abc", GoogleVoiceParser.ThreadAction.MarkRead)
        client.markVoicemailRead("t.vm")
        client.markAllRead()
        client.getSipRegisterInfo()
        val paths = transport.requests.map { it.first }
        assertTrue(paths[0].contains("api2thread/sendsms"))
        assertEquals("[prepared]", transport.requests[1].third)
        assertTrue(paths[2].contains("thread/batchupdateattributes"))
        assertTrue(paths[4].contains("thread/markallread"))
        assertEquals("[1]", transport.requests[4].third)
        assertTrue(paths[5].contains("sipregisterinfo/get"))
        Unit
    }

    @Test
    fun authAndHttpErrorsMapToExceptions() = runBlocking {
        val session = signedInSession()
        val unauthorized = GoogleVoiceClient(session) { _, _, _ ->
            SimpleResponse(401, "no", "", emptyMap(), "x")
        }
        assertFailsWith<GoogleVoiceAuthException> { unauthorized.getAccount() }
        val forbidden = GoogleVoiceClient(session) { _, _, _ ->
            SimpleResponse(403, "no", "", emptyMap(), "x")
        }
        assertFailsWith<GoogleVoiceAuthException> { forbidden.listThreads() }
        val serverError = GoogleVoiceClient(session) { _, _, _ ->
            SimpleResponse(500, "err", "boom", emptyMap(), "x")
        }
        assertFailsWith<IOException> { serverError.getAccount() }
        Unit
    }
}
