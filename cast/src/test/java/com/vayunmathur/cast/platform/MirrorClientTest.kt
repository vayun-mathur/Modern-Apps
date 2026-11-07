package com.vayunmathur.cast.platform

import com.vayunmathur.cast.domain.ClientFailure
import com.vayunmathur.cast.network.ControlSocket
import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.Ping
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.VideoCodec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

class MirrorClientTest {

    private fun connectedClient(tv: FakeTv, storedKey: ByteArray? = null): MirrorClient {
        val socket = ControlSocket("127.0.0.1", tv.port)
        socket.connect()
        return MirrorClient(socket, senderId = "phone-1", storedDeviceKey = storedKey, senderName = "Test Phone")
    }

    private fun pairedClient(tv: FakeTv): MirrorClient {
        val client = connectedClient(tv)
        assertTrue(client.begin() is HandshakeOutcome.NeedsCode)
        assertTrue(client.enterCode(FakeTv.CODE) is HandshakeOutcome.Paired)
        return client
    }

    @Test
    fun `code pairing flow with wrong and malformed codes`() {
        val tv = FakeTv()
        try {
            val client = connectedClient(tv)
            val needs = client.begin()
            val needsCode = needs as HandshakeOutcome.NeedsCode
            assertEquals(3, needsCode.attemptsLeft)
            assertEquals(false, needsCode.codeChanged)
            assertEquals("Fake TV", client.receiverName)
            assertEquals("tv-fake-1", client.receiverId)
            assertEquals(2, client.limits?.videoCodecs?.size)
            assertEquals(2, client.displayModes.size)

            // Malformed codes are refused locally and spend no attempt (signalled by -1).
            val malformed = client.enterCode("12ab") as HandshakeOutcome.NeedsCode
            assertEquals(-1, malformed.attemptsLeft)

            val wrong = client.enterCode("000000") as HandshakeOutcome.NeedsCode
            assertEquals(2, wrong.attemptsLeft)
            assertEquals(false, wrong.codeChanged)

            val paired = client.enterCode(FakeTv.CODE) as HandshakeOutcome.Paired
            assertEquals(32, paired.deviceKey?.size)
            client.sayGoodbye("done")
        } finally {
            tv.close()
        }
    }

    @Test
    fun `three wrong codes report a changed code`() {
        val tv = FakeTv()
        try {
            val client = connectedClient(tv)
            client.begin()
            client.enterCode("000000")
            client.enterCode("111111")
            val third = client.enterCode("222222") as HandshakeOutcome.NeedsCode
            assertEquals(true, third.codeChanged)
            assertEquals(3, third.attemptsLeft)
            // The gate minted a new code but our fake keeps the same digits, so pairing still works.
            assertTrue(client.enterCode(FakeTv.CODE) is HandshakeOutcome.Paired)
            client.sayGoodbye("done")
        } finally {
            tv.close()
        }
    }

    @Test
    fun `remembered device key pairs without a code`() {
        val key = ByteArray(32) { (it + 5).toByte() }
        val tv = FakeTv(codeRequired = false, rememberedKey = key)
        try {
            val client = connectedClient(tv, storedKey = key)
            val paired = client.begin() as HandshakeOutcome.Paired
            assertEquals(null, paired.deviceKey)
            client.sayGoodbye("done")
        } finally {
            tv.close()
        }
    }

    @Test
    fun `forgotten device key falls back to a code`() {
        val tv = FakeTv(codeRequired = false, rememberedKey = ByteArray(32) { 9 })
        try {
            val client = connectedClient(tv, storedKey = ByteArray(32) { 1 })
            val needs = client.begin() as HandshakeOutcome.NeedsCode
            assertEquals(3, needs.attemptsLeft)
            assertTrue(client.enterCode(FakeTv.CODE) is HandshakeOutcome.Paired)
            client.sayGoodbye("done")
        } finally {
            tv.close()
        }
    }

    @Test
    fun `version mismatch and wrong first message and bad bundle all fail distinctly`() {
        val old = FakeTv(version = 999)
        try {
            val client = connectedClient(old)
            assertEquals(
                HandshakeOutcome.Failed(ClientFailure.VersionMismatch), client.begin(),
            )
        } finally {
            old.close()
        }

        val confused = FakeTv(identityOverride = Ping)
        try {
            val client = connectedClient(confused)
            assertEquals(HandshakeOutcome.Failed(ClientFailure.Protocol), client.begin())
        } finally {
            confused.close()
        }

        val junk = FakeTv(bundleOverride = com.vayunmathur.cast.protocol.ProtocolBase64.encode(byteArrayOf(1, 2, 3)))
        try {
            val client = connectedClient(junk)
            val outcome = client.begin()
            assertTrue(outcome is HandshakeOutcome.Failed, "bad bundle should fail, got $outcome")
        } finally {
            junk.close()
        }
    }

    @Test
    fun `unconnected client fails begin and pre-pair calls are protocol failures`() {
        val socket = ControlSocket("127.0.0.1", 1) // never connected
        val client = MirrorClient(socket, senderId = "p", storedDeviceKey = null, senderName = "P")
        assertEquals(HandshakeOutcome.Failed(ClientFailure.Unreachable), client.begin())
        assertEquals(
            HandshakeOutcome.Failed(ClientFailure.Protocol),
            client.enterCode(FakeTv.CODE),
        )
        assertEquals(
            HandshakeOutcome.Failed(ClientFailure.Protocol),
            client.configureStream(640, 480, 30f, 1_000_000, VideoCodec.Hevc, audio = true, video = true),
        )
        assertEquals("Phone", MirrorClient.deviceName().ifBlank { "Phone" })
    }

    @Test
    fun `configure stream and content session negotiate with the tv`() {
        val tv = FakeTv()
        try {
            val client = pairedClient(tv)
            val ready = client.configureStream(
                1920, 1080, 30f, 8_000_000, VideoCodec.Hevc, audio = true, video = true, appLabel = "Music",
            ) as HandshakeOutcome.Ready
            assertEquals(45_678, ready.negotiation.udpPort)
            assertNotNull(ready.negotiation.audio)
            assertNotNull(ready.negotiation.video)
            assertEquals(111L, ready.negotiation.audio?.receiverSsrc)

            val content = client.openContentSession(
                host = "127.0.0.1", port = 8443,
                certificateFingerprint = ByteArray(32) { 1 }, token = "tok", video = false, appLabel = "Music",
            )
            assertEquals(ContentOutcome.Accepted, content)

            // Fire-and-forget messages reach the TV.
            client.playMedia(PlayMedia(resourceId = "song-1", mimeType = "audio/mpeg", durationMs = 1_000, startPositionMs = 0))
            client.sendNowPlaying(NowPlaying(resourceId = "song-1", title = "T", author = "A", album = "", artworkResourceId = "", lyrics = emptyList(), plainLyrics = ""))
            client.sendCodecConfig(byteArrayOf(1, 2, 3))
            client.sendPlaybackState(PlaybackState(0, 0, playing = true, buffering = false, speed = 1f, volume = 1f, hasNext = false, hasPrevious = false))
            client.sendPlaybackCommand(PlaybackCommand(PlaybackAction.Pause, null))
            client.sendPing()
            client.sendContentEnded()
            client.sayGoodbye("done")

            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline &&
                tv.received.none { it is com.vayunmathur.cast.protocol.ContentEnded }
            ) {
                Thread.sleep(10)
            }
            assertTrue(tv.received.any { it is PlayMedia })
            assertTrue(tv.received.any { it is NowPlaying })
            assertTrue(tv.received.any { it is com.vayunmathur.cast.protocol.ContentEnded })
        } finally {
            tv.close()
        }
    }

    @Test
    fun `refused content session and bad udp port are named refusals`() {
        val refusing = FakeTv(contentAccepts = false)
        try {
            val client = pairedClient(refusing)
            val outcome = client.openContentSession("127.0.0.1", 1, ByteArray(32), "t", video = true, appLabel = "")
            assertTrue(outcome is ContentOutcome.Refused)
            assertEquals("no opus here", (outcome as ContentOutcome.Refused).detail)
            client.sayGoodbye("done")
        } finally {
            refusing.close()
        }

        val badPort = FakeTv(streamPort = 0)
        try {
            val client = pairedClient(badPort)
            assertEquals(
                HandshakeOutcome.Failed(ClientFailure.StreamRefused),
                client.configureStream(640, 480, 30f, 1_000_000, VideoCodec.Hevc, audio = true, video = true),
            )
            client.sayGoodbye("done")
        } finally {
            badPort.close()
        }
    }

    @Test
    fun `awaitEnd dispatches commands and state then returns bye reason`() {
        val tv = FakeTv()
        tv.afterPair = listOf(
            PlaybackCommand(PlaybackAction.Next, null),
            PlaybackState(10, 100, playing = true, buffering = false, speed = 1f, volume = 0.5f, hasNext = true, hasPrevious = false),
            Ping,
            StreamReady(udpPort = 1, audioSsrc = 1L, videoSsrc = 2L), // nothing waiting: logged, dropped
            Bye(reason = "tv done"),
        )
        try {
            val client = pairedClient(tv)
            val commands = mutableListOf<PlaybackCommand>()
            val states = mutableListOf<PlaybackState>()
            val reason = client.awaitEnd({ commands += it }, { states += it })
            assertEquals("tv done", reason)
            assertEquals(listOf(PlaybackAction.Next), commands.map { it.action })
            assertEquals(listOf(10L), states.map { it.positionMs })
        } finally {
            tv.close()
        }
    }

    @Test
    fun `awaitEnd with no callbacks drops commands and ends on socket close`() {
        val tv = FakeTv()
        tv.afterPair = listOf(PlaybackCommand(PlaybackAction.Play, null))
        try {
            val client = pairedClient(tv)
            // TV closes without a Bye: awaitEnd returns null. Close from here after a beat.
            Thread {
                Thread.sleep(300)
                tv.close()
            }.start()
            assertEquals(null, client.awaitEnd())
        } finally {
            tv.close()
        }
    }

    @Test
    fun `reconfigure hands stream ready through awaitEnd`() = runBlocking {
        val tv = FakeTv()
        try {
            val client = pairedClient(tv)
            val watcher = async(kotlinx.coroutines.Dispatchers.IO) { client.awaitEnd() }
            Thread.sleep(200) // let awaitEnd become the socket reader
            val ready = client.reconfigureStream(
                1280, 720, 30f, 4_000_000, VideoCodec.Hevc, audio = true, video = true,
            ) as HandshakeOutcome.Ready
            assertEquals(45_678, ready.negotiation.udpPort)
            client.sayGoodbye("bye-now")
            tv.close() // TV hanging up ends awaitEnd with no Bye reason
            assertEquals(null, watcher.await())
            Unit
        } finally {
            tv.close()
        }
    }
}
