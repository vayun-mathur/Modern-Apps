package com.vayunmathur.youpipe.util

import com.vayunmathur.youpipe.ui.VideoInfo
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Slice 2/3 (w5): the StreamInfoItem -> VideoInfo mapping in util/
 * Extractor.kt (pure model construction, no network). Robolectric: the
 * mapping decodes HTML via HtmlCompat.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice2ExtractorNetTest {

    private fun streamItem(
        name: String,
        date: DateWrapper?,
        views: Long = 1_000,
        paid: Boolean = false,
    ): StreamInfoItem {
        val id = decodeVideoID(4242L)
        val item = StreamInfoItem(0, "https://www.youtube.com/watch?v=$id", name, StreamType.VIDEO_STREAM)
        item.setUploadDate(date)
        item.setViewCount(views)
        item.setDuration(120)
        item.setUploaderName("Some Author")
        item.setThumbnails(listOf(Image("https://example.com/t.jpg", 90, 160, Image.ResolutionLevel.MEDIUM)))
        item.setContentAvailability(if (paid) ContentAvailability.PAID else ContentAvailability.AVAILABLE)
        return item
    }

    private fun dateOf(epochSecond: Long): DateWrapper =
        DateWrapper(
            java.time.OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(epochSecond), java.time.ZoneOffset.UTC,
            ),
        )

    @Test
    fun toVideoInfo_mapsAllFields() {
        val info: VideoInfo? = streamItem("Fish &amp; Chips", dateOf(1_700_000_000L)).toVideoInfo()
        assertTrue(info != null)
        assertEquals("Fish & Chips", info.name)
        assertEquals(4242L, info.videoID)
        assertEquals(120L, info.duration)
        assertEquals(1_000L, info.views)
        assertEquals(1_700_000_000L, info.uploadDate.epochSeconds)
        assertEquals("https://example.com/t.jpg", info.thumbnailURL)
        assertEquals("Some Author", info.author)
        assertEquals(false, info.isPaid)
    }

    @Test
    fun toVideoInfo_nullWithoutUploadDate() {
        assertNull(streamItem("No date", null).toVideoInfo())
    }

    @Test
    fun toVideoInfo_paidFlagPropagates() {
        val info = streamItem("Paid", dateOf(1_700_000_000L), paid = true).toVideoInfo()
        assertEquals(true, info?.isPaid)
    }

    @Test
    fun toVideoInfo_emptyThumbnailFallsBackToBlank() {
        val item = streamItem("No thumb", dateOf(1_700_000_000L))
        item.setThumbnails(emptyList())
        assertEquals("", item.toVideoInfo()?.thumbnailURL)
    }

}
