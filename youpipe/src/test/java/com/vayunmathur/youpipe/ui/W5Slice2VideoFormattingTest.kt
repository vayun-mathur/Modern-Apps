package com.vayunmathur.youpipe.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.runner.RunWith

/**
 * Slice 2/3 (w5): VideoFormatting.kt pure helpers (codec names, count strings,
 * HTML stripping). Robolectric for the Context-backed string resources.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice2VideoFormattingTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun videoCodecNames() {
        assertEquals("av1", getVideoCodecName("av01.0.05M.08"))
        assertEquals("vp9", getVideoCodecName("vp9"))
        assertEquals("vp9", getVideoCodecName("vp09.00.10.08"))
        assertEquals("avc", getVideoCodecName("avc1.640028"))
        assertEquals("avc", getVideoCodecName("H264-baseline"))
        assertEquals("hevc", getVideoCodecName("hevc"))
    }

    @Test
    fun audioCodecNames() {
        assertEquals("opus", getAudioCodecName("opus"))
        assertEquals("opus", getAudioCodecName("Opus audio"))
        assertEquals("aac", getAudioCodecName("mp4a.40.2"))
        assertEquals("aac", getAudioCodecName("AAC-LC"))
        assertEquals("vorbis", getAudioCodecName("vorbis"))
    }

    @Test
    fun countString_shapes() {
        assertEquals("999", countString(context, 999))
        assertEquals("0", countString(context, 0))
        val k = countString(context, 12_345)
        assertTrue(k.endsWith("K"), k)
        val m = countString(context, 2_500_000)
        assertTrue(m.endsWith("M"), m)
        val b = countString(context, 1_500_000_000)
        assertTrue(b.endsWith("B"), b)
        // 4-digit uses 2 decimals, 6-digit none
        assertTrue(countString(context, 1234).contains("."), countString(context, 1234))
    }

    @Test
    fun fromHtml_stripsBreaksAndEntities() {
        // fromHTML maps <br> to \n, then HtmlCompat collapses it to a space
        // (real fromHtml whitespace handling, not a Robolectric artifact).
        assertEquals("a b", "a<br>b".fromHTML())
        assertEquals("Fish & Chips", "Fish &amp; Chips".fromHTML())
    }

    @Test
    fun uploadTimeAgo_buckets() {
        val now = kotlin.time.Clock.System.now()
        assertEquals("Just now", uploadTimeAgo(context, now))
        assertEquals(
            "Just now",
            uploadTimeAgo(context, now - 3.minutes),
        )
        val mins = uploadTimeAgo(context, now - 30.minutes)
        assertTrue(mins.contains("30"), mins)
        val hoursStr = uploadTimeAgo(context, now - 5.hours)
        assertTrue(hoursStr.contains("5"), hoursStr)
        // future date -> just now
        assertEquals("Just now", uploadTimeAgo(context, now + 1.hours))
    }
}
