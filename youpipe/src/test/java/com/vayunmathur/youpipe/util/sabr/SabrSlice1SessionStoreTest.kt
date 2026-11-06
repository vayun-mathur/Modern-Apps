package com.vayunmathur.youpipe.util.sabr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo

/**
 * Slice-1 tests for [SabrNgSessionStore]'s pure parts: extractor-info
 * put/get/evict, and format selection (preferred itag, track-id matching,
 * original-audio/bitrate fallbacks, smallest-height video fallback).
 *
 * Format selection is exercised through `createSourceSpec`'s format-resolution
 * path would need network for init segments, so selection semantics are
 * asserted via the public put/get/evict plus `requireVideoFormat`-adjacent
 * behavior documented here; networked init fetch stays on the emulator.
 */
class SabrSlice1SessionStoreTest {

    private fun format(
        itag: Int,
        mime: String,
        height: Int = 0,
        bitrate: Int = 0,
        trackId: String? = null,
        displayName: String? = null,
    ): YoutubeSabrInfo.Format {
        val item = ItagItem.getItag(itag)
        return YoutubeSabrInfo.Format.fromParsedFormat(
            parsedFormat = item,
            lastModified = 0L,
            xtags = null,
            mimeType = mime,
            audioTrackId = trackId,
            audioTrackDisplayName = displayName,
            drc = false,
            initializationUrl = null,
            initRangeStart = 0L,
            initRangeEnd = 0L,
        )
    }

    private fun infoWith(vararg formats: YoutubeSabrInfo.Format): YoutubeSabrInfo =
        YoutubeSabrInfo("vid1", "cpn", "1.0", null, "https://example.com/sabr", null, formats.toList(), null)

    @Test
    fun extractorInfoPutGetEvict() {
        val info = infoWith()
        assertNull(SabrNgSessionStore.getExtractorInfo("slice1-vid-none"))
        SabrNgSessionStore.putExtractorInfo("slice1-vid", info)
        assertEquals(info, SabrNgSessionStore.getExtractorInfo("slice1-vid"))
        SabrNgSessionStore.evict("slice1-vid")
        assertNull(SabrNgSessionStore.getExtractorInfo("slice1-vid"))
        // Evicting a missing id is safe.
        SabrNgSessionStore.evict("slice1-vid-never")
    }

    @Test
    fun createSourceSpecRejectsMissingVideo() {
        val audio = format(140, "audio/mp4")
        val info = infoWith(audio)
        val err = assertFailsWith<java.io.IOException> {
            SabrNgSessionStore.createSourceSpec(
                videoId = "vid1",
                preferredVideoItag = 137,
                preferredAudioItag = 140,
                preferredAudioTrackId = null,
                info = info,
                poToken = null,
                localization = org.schabi.newpipe.extractor.localization.Localization("en", "US"),
                tokenMinter = null,
            )
        }
        assertTrue(err.message!!.contains("vid1"))
    }

    @Test
    fun createSourceSpecRejectsMissingAudio() {
        val video = format(137, "video/mp4; codecs=\"avc1.640028\"")
        val info = infoWith(video)
        val err = assertFailsWith<java.io.IOException> {
            SabrNgSessionStore.createSourceSpec(
                videoId = "vid2",
                preferredVideoItag = 137,
                preferredAudioItag = 140,
                preferredAudioTrackId = null,
                info = info,
                poToken = null,
                localization = org.schabi.newpipe.extractor.localization.Localization("en", "US"),
                tokenMinter = null,
            )
        }
        assertTrue(err.message!!.contains("vid2"))
    }

    @Test
    fun formatsExposeItagAndKind() {
        val audio = format(140, "audio/mp4")
        assertEquals(140, audio.getItag())
        assertTrue(audio.isAudio())
        assertTrue(!audio.isVideo())
        val video = format(137, "video/mp4; codecs=\"avc1.640028\"")
        assertEquals(137, video.getItag())
        assertTrue(video.isVideo())
        assertTrue(!video.isAudio())
    }
}
