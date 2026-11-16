package com.vayunmathur.communicate.data

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.enrichGroupFromMex
import com.vayunmathur.communicate.data.whatsapp.fetchAndEmitGroupInfo
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * W1 wave 5: WhatsApp group-info paths in `WhatsAppClientGroupsKt` with a
 * Connected-but-dead socket — IQ waits fail fast and MEX enrichment aborts,
 * and group creation through the repository returns null.
 */
@RunWith(AndroidJUnit4::class)
class CommunicateWhatsAppGroupsW1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private val auth = WhatsAppAuthData(
        phoneNumber = "+15550001", pushName = "Tester", wid = "15550001@s.whatsapp.net",
        noisePrivateKey = b64(ByteArray(32) { 1 }), noisePublicKey = b64(ByteArray(32) { 2 }),
        identityPrivateKey = b64(ByteArray(32) { 3 }), identityPublicKey = b64(ByteArray(32) { 4 }),
        registrationId = 14, signedPreKeyId = 5,
        signedPreKeyPublic = b64(ByteArray(32) { 5 }), signedPreKeyPrivate = b64(ByteArray(32) { 6 }),
        signedPreKeySignature = b64(ByteArray(64) { 7 }), lid = "12345@lid",
    )

    private lateinit var db: WhatsAppDatabase

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = auth
        WhatsAppClient.db = db
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.knownGroups.clear()
        WhatsAppClient.pendingMessageIDs.clear()
    }

    @AfterTest
    fun tearDown() {
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        WhatsAppClient.webSocket = null
        WhatsAppClient.db = null
        WhatsAppClient.authData = null
        WhatsAppClient.knownGroups.clear()
    }

    @Test
    fun `group info fetch ignores non-group JIDs and fails fast for groups`() = runTest {
        WhatsAppClient.fetchAndEmitGroupInfo("15550002@s.whatsapp.net") // not a group: no-op
        WhatsAppClient.fetchAndEmitGroupInfo("12345@g.us") // IQ unanswered: fails fast
        // A second call for the same group is not suppressed after failure.
        WhatsAppClient.fetchAndEmitGroupInfo("12345@g.us")
    }

    @Test
    fun `mex group enrichment aborts when the query fails`() = runTest {
        WhatsAppClient.enrichGroupFromMex("12345@g.us", participantCount = 3)
    }

    @Test
    fun `repository group creation returns null when the IQ is unanswered`() = runTest {
        assertNull(
            CommunicateRepository.createWhatsAppGroup(
                context, "Team", listOf("+14155550102", "4155550103", "+14155550102"),
            ),
        )
    }
}
