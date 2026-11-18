package com.vayunmathur.findfamily.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.util.DataStoreUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Direct-boot identity bootstrap ([Networking.initDirectBoot] /
 * [Networking.promoteToUnlocked]) and the malformed-peer-bundle behaviour of the
 * publish path: a corrupt stored bundle must read as "no usable bundle"
 * (false/null), never as an exception escaping toward the heartbeat.
 */
@RunWith(AndroidJUnit4::class)
class NetworkingDirectBootTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest
    fun setup() = runBlocking {
        Networking.stopLive()
        val repo = TestFindFamilyRepository.install(ctx)
        Networking.repository = repo
        Unit
    }

    private fun fakeSession(output: ByteArrayOutputStream): WsSession {
        val clientConstructor = WebSocketClient::class.java
            .getDeclaredConstructor(
                Socket::class.java, java.io.InputStream::class.java,
                java.io.OutputStream::class.java, Map::class.java, Map::class.java,
            ).apply { isAccessible = true }
        val client = clientConstructor.newInstance(
            Socket(), ByteArrayInputStream(ByteArray(0)), output,
            emptyMap<String, List<String>>(), emptyMap<String, String>(),
        )
        val sessionConstructor = WsSession::class.java
            .getDeclaredConstructor(WebSocketClient::class.java)
            .apply { isAccessible = true }
        return sessionConstructor.newInstance(client)
    }

    private fun peer(id: Long, bundle: String?, outboundPairId: String? = "pair-x") = User(
        name = "Peer",
        photo = null,
        locationName = "Home",
        sendingEnabled = true,
        requestStatus = RequestStatus.MUTUAL_CONNECTION,
        lastLocationChangeTime = Clock.System.now(),
        id = id,
        pqcEncryptionKey = bundle,
        outboundPairId = outboundPairId,
    )

    private fun fix() = LocationValue(
        userid = Networking.userid,
        coord = Coord(1.0, 2.0),
        speed = 0f,
        acc = 5f,
        timestamp = Clock.System.now(),
        battery = 50f,
    )

    @Test fun malformedPeerBundleFailsClosed() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        Networking.repository = repo
        Networking.init(repo, DataStoreUtils.getInstance(ctx), "Me")
        assertTrue(Networking.pqcReady)
        val bad = peer(901L, bundle = "!!!not-base64!!!")
        repo.upsertUser(bad)
        // Documented contract: publish returns false when the peer has no usable
        // bundle; a corrupt stored value is exactly that, not an exception.
        assertFalse(Networking.publishLocation(fix(), bad))
        assertNull(Networking.securityCode(bad))
        assertNull(Networking.securityCodePqc(bad))
        val envelope = com.vayunmathur.findfamily.domain.UwbEnvelope(
            sessionId = "s", sender = 1uL, senderPlatform = "android", kind = "request"
        )
        assertFalse(Networking.publishUwbMessage(envelope, 901L, bad))
        Unit
    }

    @Test fun outboundPairIdIsGeneratedOnceAndReused() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        Networking.repository = repo
        Networking.init(repo, DataStoreUtils.getInstance(ctx), "Me")
        val out = ByteArrayOutputStream()
        Networking.wsSession = fakeSession(out)
        val bundle = Base64.encode(Networking.pqcIdentity.publicBundle)
        val id = repo.upsertUser(peer(0L, bundle = bundle, outboundPairId = null).copy(name = "Peer"))
        val stored = repo.getUser(id)!!
        assertNull(stored.outboundPairId)
        assertTrue(Networking.publishLocation(fix(), stored))
        val generated = repo.getUser(id)!!.outboundPairId
        assertNotNull(generated)
        // Second publish reuses the pinned value instead of rotating it.
        out.reset()
        assertTrue(Networking.publishLocation(fix(), repo.getUser(id)!!))
        assertEquals(generated, repo.getUser(id)!!.outboundPairId)
        Networking.wsSession = null
    }
}
