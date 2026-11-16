package com.vayunmathur.cast.tv.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.ProtocolBase64
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Decoder limits, network label, and artwork fetcher failure paths on the host. */
@RunWith(RobolectricTestRunner::class)
class CompanionDeepTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun videoAndAudioLimitsReflectHostCodecs() {
        // Robolectric enumerates no hardware decoders, so both limit queries must
        // answer with their "nothing to advertise" shape rather than throwing.
        val video = VideoDecoder.limits()
        assertNotNull(video.videoCodecs)
        for (codec in com.vayunmathur.cast.protocol.VideoCodec.entries) {
            val name = VideoDecoder.decoderName(codec)
            assertTrue(name == null || name.isNotBlank())
        }
        val audio = AudioPlayer.limits()
        assertTrue(audio.isEmpty() || audio.single().label.isNotBlank())
        AudioPlayer.decoderName() // null or a codec name; must not throw either way
    }

    @Test fun networkLabelNeverThrows() {
        // SSID/IP halves both fail open on a headless host; the joined label is
        // whatever could be read, possibly empty.
        val label = NetworkLabel.of(ctx)
        assertNotNull(label)
    }

    @Test fun artworkFetcherRejectsBadFingerprintsAndUnreachableHosts() {
        val badFingerprint = ArtworkFetcher(
            ContentSession(
                host = "127.0.0.1", port = 9,
                certificateFingerprint = "not-base64!!",
                token = "tok", video = true,
            ),
        )
        assertNull(badFingerprint.fetch("cover-1"))

        val shortFingerprint = ArtworkFetcher(
            ContentSession(
                host = "127.0.0.1", port = 9,
                certificateFingerprint = ProtocolBase64.encode(ByteArray(8)),
                token = "tok", video = true,
            ),
        )
        assertNull(shortFingerprint.fetch("cover-1"))

        val unreachable = ArtworkFetcher(
            ContentSession(
                host = "127.0.0.1", port = 9,
                certificateFingerprint = ProtocolBase64.encode(ByteArray(32) { it.toByte() }),
                token = "tok", video = true,
            ),
        )
        // A well-formed fingerprint reaches the fetch, which fails soft (refused).
        assertNull(unreachable.fetch("cover-1"))
        assertEquals(32, ProtocolBase64.decode(ProtocolBase64.encode(ByteArray(32)))?.size)
    }
}
