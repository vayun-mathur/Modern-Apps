package com.vayunmathur.youpipe.ui

import com.vayunmathur.youpipe.util.trustedThumbnailUrl
import com.vayunmathur.youpipe.util.trustedTitle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Slice 2/3 (w5): pure video-ID / URL / branding helpers in util/Extractor.kt
 * (A-L root slice). Robolectric: URL helpers go through androidx.core Uri.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice2ExtractorTest {

    @Test
    fun videoId_encodesAndDecodesRoundTrip() {
        val id = 123456789012345L
        val encoded = com.vayunmathur.youpipe.util.decodeVideoID(id)
        assertEquals(id, com.vayunmathur.youpipe.util.encodeVideoID(encoded))
    }

    @Test
    fun videoUrl_roundTripsThroughId() {
        val id = 987654321L
        val url = com.vayunmathur.youpipe.util.videoIDtoURL(id)
        assertTrue(url.startsWith("https://www.youtube.com/watch?v="), url)
        assertEquals(id, com.vayunmathur.youpipe.util.videoURLtoID(url))
    }

    @Test
    fun parseSharedVideoId_findsAllUrlShapes() {
        // 11-char id "dQw4w9WgXcQ" style
        val id11 = "dQw4w9WgXcQJ"
        val expected = com.vayunmathur.youpipe.util.encodeVideoID(id11)
        assertEquals(expected, com.vayunmathur.youpipe.util.parseSharedVideoId("https://www.youtube.com/watch?v=$id11"))
        assertEquals(expected, com.vayunmathur.youpipe.util.parseSharedVideoId("https://youtu.be/$id11"))
        assertEquals(expected, com.vayunmathur.youpipe.util.parseSharedVideoId("check this https://www.youtube.com/shorts/$id11 wow"))
        assertEquals(expected, com.vayunmathur.youpipe.util.parseSharedVideoId("https://www.youtube.com/embed/$id11"))
        assertNull(com.vayunmathur.youpipe.util.parseSharedVideoId("no link here"))
        assertNull(com.vayunmathur.youpipe.util.parseSharedVideoId("https://www.youtube.com/watch?v=short"))
    }

    @Test
    fun channelUrls_handleAndIdShapes() {
        assertEquals(
            "https://www.youtube.com/@SomeChannel",
            com.vayunmathur.youpipe.util.channelIDtoURL("@SomeChannel"),
        )
        assertEquals(
            "https://www.youtube.com/channel/UC12345",
            com.vayunmathur.youpipe.util.channelIDtoURL("UC12345"),
        )
        assertEquals("UC12345", com.vayunmathur.youpipe.util.channelURLtoID("https://www.youtube.com/channel/UC12345"))
    }

    @Test
    fun sponsorSegment_startEndMillis() {
        val seg = com.vayunmathur.youpipe.util.SponsorSegment("sponsor", listOf(12.5f, 67.25f), "uuid-1")
        assertEquals(12_500L, seg.start)
        assertEquals(67_250L, seg.end)
    }

    @Test
    fun deArrowTrustedTitle_rules() {
        fun branding(title: String, original: Boolean, votes: Int, locked: Boolean) =
            com.vayunmathur.youpipe.util.DeArrowBranding(
                titles = listOf(
                    com.vayunmathur.youpipe.util.DeArrowTitle(title, original, votes, locked, "u"),
                ),
                thumbnails = emptyList(),
                randomTime = 0.0,
            )
        assertEquals("Better Title", branding("Better Title>", false, 10, true).trustedTitle())
        assertNull(branding("Original", true, 100, true).trustedTitle())
        assertNull(branding("Downvoted", false, -5, false).trustedTitle())
        // locked overrides negative votes
        assertEquals("Locked", branding("Locked", false, -5, true).trustedTitle())
        assertNull(
            com.vayunmathur.youpipe.util.DeArrowBranding(emptyList(), emptyList(), 0.0).trustedTitle(),
        )
    }

    @Test
    fun deArrowThumbnailUrl_rules() {
        val id = 424242L
        fun branding(thumb: com.vayunmathur.youpipe.util.DeArrowThumbnail?) =
            com.vayunmathur.youpipe.util.DeArrowBranding(
                titles = emptyList(),
                thumbnails = listOfNotNull(thumb),
                randomTime = 0.0,
            )
        val good = branding(com.vayunmathur.youpipe.util.DeArrowThumbnail(12.5, false, 3, false, "u"))
        val url = good.trustedThumbnailUrl(id)!!
        assertTrue(url.contains("time=12.5"), url)
        assertTrue(url.contains(com.vayunmathur.youpipe.util.decodeVideoID(id)), url)
        assertNull(branding(com.vayunmathur.youpipe.util.DeArrowThumbnail(1.0, true, 3, false, "u")).trustedThumbnailUrl(id))
        assertNull(branding(com.vayunmathur.youpipe.util.DeArrowThumbnail(1.0, false, -9, false, "u")).trustedThumbnailUrl(id))
        assertNull(branding(com.vayunmathur.youpipe.util.DeArrowThumbnail(null, false, 3, false, "u")).trustedThumbnailUrl(id))
        assertNull(branding(null).trustedThumbnailUrl(id))
    }
}
