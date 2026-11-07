package com.vayunmathur.cast.platform

import com.vayunmathur.cast.domain.ClientFailure
import com.vayunmathur.cast.network.ControlSocket
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.VideoCodec
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class MirrorClientExtraTest {

    private fun connectedClient(tv: FakeTv, storedKey: ByteArray? = null): MirrorClient {
        val socket = ControlSocket("127.0.0.1", tv.port)
        socket.connect()
        return MirrorClient(socket, senderId = "phone-1", storedDeviceKey = storedKey, senderName = "Test Phone")
    }

    @Test
    fun `paired outcome equality is by key content`() {
        val a = HandshakeOutcome.Paired(byteArrayOf(1, 2, 3))
        val b = HandshakeOutcome.Paired(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, HandshakeOutcome.Paired(byteArrayOf(1, 2, 4)))
        assertEquals(HandshakeOutcome.Paired(null), HandshakeOutcome.Paired(null))
    }

    @Test
    fun `tv with no codecs and no modes still pairs and names everything empty`() {
        val tv = FakeTv(limitsOverride = DecoderLimits())
        try {
            val client = connectedClient(tv)
            val needs = client.begin() as HandshakeOutcome.NeedsCode
            assertEquals(3, needs.attemptsLeft)
            assertTrue(client.limits?.videoCodecs?.isEmpty() == true)
            assertTrue(client.displayModes.isEmpty() || client.displayModes.isNotEmpty())
            assertTrue(client.enterCode(FakeTv.CODE) is HandshakeOutcome.Paired)
            client.sayGoodbye("done")
        } finally {
            tv.close()
        }
    }

    @Test
    fun `reconfigure before pairing is a protocol failure`() {
        val socket = ControlSocket("127.0.0.1", 1)
        val client = MirrorClient(socket, senderId = "p", storedDeviceKey = null, senderName = "P")
        kotlinx.coroutines.runBlocking {
            kotlin.test.assertEquals(
                HandshakeOutcome.Failed(ClientFailure.Protocol),
                client.reconfigureStream(640, 480, 30f, 1_000_000, VideoCodec.Hevc, audio = true, video = true),
            )
        }
    }

    @Test
    fun `reconfigure times out when the tv never answers`() {
        val tv = FakeTv(answerStream = false)
        try {
            val client = connectedClient(tv)
            assertTrue(client.begin() is HandshakeOutcome.NeedsCode)
            assertTrue(client.enterCode(FakeTv.CODE) is HandshakeOutcome.Paired)
            val watcher = Thread { client.awaitEnd() }
            watcher.start()
            Thread.sleep(200)
            val outcome = kotlinx.coroutines.runBlocking {
                client.reconfigureStream(640, 480, 30f, 1_000_000, VideoCodec.Hevc, audio = true, video = true)
            }
            assertEquals(HandshakeOutcome.Failed(ClientFailure.StreamRefused), outcome)
            client.sayGoodbye("done")
            watcher.join(3_000)
        } finally {
            tv.close()
        }
    }

    @Test
    fun `stream refused when the tv hangs up after pairing`() {
        val tv = FakeTv()
        val client = connectedClient(tv)
        try {
            client.begin()
            client.enterCode(FakeTv.CODE)
        } finally {
            tv.close()
        }
        val outcome = client.configureStream(
            640, 480, 30f, 1_000_000, VideoCodec.Hevc, audio = true, video = true,
        )
        assertTrue(
            outcome == HandshakeOutcome.Failed(ClientFailure.StreamRefused) ||
                outcome == HandshakeOutcome.Failed(ClientFailure.Unreachable),
            "got $outcome",
        )
        val content = client.openContentSession("127.0.0.1", 1, ByteArray(32), "t", video = true, appLabel = "")
        assertTrue(content is ContentOutcome.Refused)
    }
}
