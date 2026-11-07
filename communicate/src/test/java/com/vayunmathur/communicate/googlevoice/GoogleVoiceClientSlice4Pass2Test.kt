package com.vayunmathur.communicate.googlevoice

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceAuthException
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.GvFolder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: GV client auth guard — every RPC fails fast with no session, before any network. */
@RunWith(RobolectricTestRunner::class)
class GoogleVoiceClientSlice4Pass2Test {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun clientGetReturnsUsableInstance() {
        val client = GoogleVoiceClient.get(ctx)
        assertNotNull(client)
        assertNotNull(GoogleVoiceSession.get(ctx))
    }

    @Test
    fun everyRpcThrowsAuthWithoutCredentials() = runBlocking {
        val client = GoogleVoiceClient(GoogleVoiceSession.get(ctx))
        // Ensure no credentials are present in this fresh Robolectric store.
        assertFailsWith<GoogleVoiceAuthException> { client.getAccount() }
        assertFailsWith<GoogleVoiceAuthException> { client.listThreads(GvFolder.Inbox) }
        assertFailsWith<GoogleVoiceAuthException> { client.listThreads() }
        assertFailsWith<GoogleVoiceAuthException> { client.getThread("remote-1") }
        assertFailsWith<GoogleVoiceAuthException> { client.search("hello") }
        assertFailsWith<GoogleVoiceAuthException> { client.listCalls() }
        assertFailsWith<GoogleVoiceAuthException> { client.listVoicemails() }
        assertFailsWith<GoogleVoiceAuthException> { client.markVoicemailRead("remote-1") }
        assertFailsWith<GoogleVoiceAuthException> { client.sendSms("+1555", "hi") }
        assertFailsWith<GoogleVoiceAuthException> { client.sendSms("+1555", "hi", "thread-1") }
        assertFailsWith<GoogleVoiceAuthException> { client.sendPreparedSms("{}") }
        assertFailsWith<GoogleVoiceAuthException> {
            client.updateThreadAttributes("remote-1", GoogleVoiceParser.ThreadAction.MarkRead)
        }
        assertFailsWith<GoogleVoiceAuthException> { client.markAllRead() }
        assertFailsWith<GoogleVoiceAuthException> { client.getSipRegisterInfo() }
        Unit
    }
}
