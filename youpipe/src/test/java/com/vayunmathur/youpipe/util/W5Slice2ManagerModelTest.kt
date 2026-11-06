package com.vayunmathur.youpipe.util

import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Slice 2/3 (w5): JVM-pure coverage of the A-L slice's state holders —
 * [DownloadManager]'s progress map (the WorkManager entry points need a real
 * WorkManager and are covered on-device), plus the pure model mappings in
 * ChannelPage.kt (`ChannelInfo.toSubscription`) and Extractor.kt
 * (`SponsorSegment` bounds, `DeArrow*` edge cases beyond the main rules).
 */
class W5Slice2ManagerModelTest {

    private fun video(id: Long) = VideoInfo(
        name = "v$id",
        videoID = id,
        duration = 100,
        views = 1000,
        uploadDate = Instant.fromEpochSeconds(1_700_000_000L),
        thumbnailURL = "https://example.com/$id.jpg",
        author = "author",
    )

    @Test
    fun updateProgress_ignoresUnknownIds() {
        // The only writer of new entries is enqueueDownload, which needs a real
        // WorkManager (on-device). updateProgress/finishDownload on absent ids
        // must be strict no-ops rather than creating phantom rows.
        val before = DownloadManager.activeDownloads.value
        DownloadManager.updateProgress(videoID = -999_999L, progress = 0.5)
        DownloadManager.finishDownload(-999_999L)
        assertEquals(before, DownloadManager.activeDownloads.value)
    }

    @Test
    fun downloadStatus_holdsVideoAndProgress() {
        val info = video(5)
        val status = DownloadStatus(info, 0.25)
        assertEquals(info, status.videoInfo)
        assertEquals(0.25, status.progress)
        assertEquals(0.75, status.copy(progress = 0.75).progress)
    }

    @Test
    fun channelInfo_toSubscriptionMapsFields() {
        val info = ChannelInfo(
            name = "Some Channel",
            channelID = "UC12345",
            subscribers = 12_345,
            videos = 100,
            avatar = "https://example.com/a.jpg",
        )
        val sub = info.toSubscription()
        assertEquals("Some Channel", sub.name)
        assertEquals("UC12345", sub.channelID)
        assertEquals("https://example.com/a.jpg", sub.avatarURL)
    }

    @Test
    fun sponsorSegment_truncatesTowardZero() {
        // 12.999s -> 12999ms, not 13000: (Float * 1000).toLong() truncates.
        val seg = SponsorSegment("intro", listOf(12.999f, 67.001f), "u")
        assertEquals(12_999L, seg.start)
        assertEquals(67_001L, seg.end)
    }

    @Test
    fun deArrowTitle_trimsWhitespaceAndGT() {
        val branding = DeArrowBranding(
            titles = listOf(DeArrowTitle("  Padded Title>> ", false, 0, false, "u")),
            thumbnails = emptyList(),
            randomTime = 0.0,
        )
        assertEquals("Padded Title", branding.trustedTitle())
    }

    @Test
    fun deArrowThumbnail_zeroTimestampKept() {
        // timestamp 0.0 is a real frame; only null means "no thumbnail".
        val branding = DeArrowBranding(
            titles = emptyList(),
            thumbnails = listOf(DeArrowThumbnail(0.0, false, 1, false, "u")),
            randomTime = 0.0,
        )
        val url = branding.trustedThumbnailUrl(7L)
        assertTrue(url != null && url.contains("time=0.0"), url)
    }
}
