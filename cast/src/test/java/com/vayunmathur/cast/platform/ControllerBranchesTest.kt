package com.vayunmathur.cast.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.Route
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientFailure
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import com.vayunmathur.cast.network.ControlSocket
import com.vayunmathur.cast.protocol.ContentEnded
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControllerBranchesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() = runBlocking { CastController.teardown() }

    private suspend fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timeout; phase=${CastController.sessionState.value.phase}")
            }
            delay(15)
        }
    }

    private fun device(port: Int) =
        CastDevice(id = "tv-fake-1", friendlyName = "Fake TV", host = "127.0.0.1", port = port)

    @Test
    fun `route round trips through its serializer`() {
        val json = Json.encodeToString(Route.serializer(), Route.Cast)
        assertEquals(Route.Cast, Json.decodeFromString(Route.serializer(), json))
        assertTrue(Route.Cast is com.vayunmathur.library.util.NavKey)
    }

    @Test
    fun `connect fails when the handshake reports a new version`() = runBlocking {
        val tv = FakeTv(version = 999)
        try {
            CastController.connect(context, device(tv.port).copy(protocolVersion = 0), thenMirror = false)
            await { CastController.sessionState.value.phase == ClientPhase.Failed }
            assertEquals(ClientFailure.VersionMismatch, CastController.sessionState.value.failure)
        } finally {
            tv.close()
        }
        Unit
    }

    @Test
    fun `submitting a code after the tv hung up fails the session`() = runBlocking {
        val tv = FakeTv()
        CastController.connect(context, device(tv.port), thenMirror = false)
        await { CastController.sessionState.value.phase == ClientPhase.AwaitingCode }
        tv.close()
        CastController.submitPairCode(context, FakeTv.CODE)
        await { CastController.sessionState.value.phase == ClientPhase.Failed }
        assertEquals(ClientFailure.Unreachable, CastController.sessionState.value.failure)
        Unit
    }

    @Test
    fun `three wrong codes mark the code changed with a fresh allowance`() = runBlocking {
        val tv = FakeTv()
        try {
            CastController.connect(context, device(tv.port), thenMirror = false)
            await { CastController.sessionState.value.phase == ClientPhase.AwaitingCode }
            CastController.submitPairCode(context, "000000")
            await { CastController.sessionState.value.attemptsLeft == 2 }
            CastController.submitPairCode(context, "111111")
            await { CastController.sessionState.value.attemptsLeft == 1 }
            CastController.submitPairCode(context, "222222")
            await { CastController.sessionState.value.codeChanged }
            assertEquals(3, CastController.sessionState.value.attemptsLeft)
            assertEquals(ClientPhase.AwaitingCode, CastController.sessionState.value.phase)
        } finally {
            tv.close()
        }
        Unit
    }

    @Test
    fun `served session without a controller socket has nowhere to serve from`() = runBlocking {
        // A paired client installed by hand, with no ControlSocket beside it:
        // the served path needs the socket's local address for the proxy host.
        val tv = FakeTv()
        try {
            val socket = ControlSocket("127.0.0.1", tv.port)
            socket.connect()
            val client = MirrorClient(socket, senderId = "phone-1", storedDeviceKey = null, senderName = "P")
            assertTrue(client.begin() is HandshakeOutcome.NeedsCode)
            assertTrue(client.enterCode(FakeTv.CODE) is HandshakeOutcome.Paired)
            CastController.client = client
            CastController.deviceMutable.value = device(tv.port)
            CastController.sessionStateMutable.value = ClientState(phase = ClientPhase.Paired)
            CastController.socket = null

            val result = CastController.startContentSession(
                context, 640, 480, wantAudio = true, appLabel = "Music",
                resources = { null },
            )
            assertTrue(result is ContentSessionResult.Failed, "got $result")
            assertEquals(MirrorPhase.Failed, CastController.mirrorPhase.value)
            client.sayGoodbye("done")
        } finally {
            tv.close()
        }
        Unit
    }

    @Test
    fun `stop mirroring tells a served tv the content ended`() = runBlocking {
        val tv = FakeTv()
        try {
            CastController.connect(context, device(tv.port), thenMirror = false)
            await { CastController.sessionState.value.phase == ClientPhase.AwaitingCode }
            CastController.submitPairCode(context, FakeTv.CODE)
            await { CastController.sessionState.value.phase == ClientPhase.Paired }
            val result = CastController.startContentSession(
                context, 640, 480, wantAudio = false, appLabel = "Music",
                wantVideo = false,
                resources = { null },
            )
            assertTrue(result is ContentSessionResult.Serving, "got $result")
            assertEquals(false, (result as ContentSessionResult.Serving).hasVideo)
            CastController.stopMirroring(context)
            await { tv.received.any { it is ContentEnded } }
            await { CastController.mirrorPhase.value == MirrorPhase.Idle }
            // Back to paired-but-idle on the same channel.
            assertEquals(ClientPhase.Paired, CastController.sessionState.value.phase)
        } finally {
            tv.close()
        }
        Unit
    }
}
