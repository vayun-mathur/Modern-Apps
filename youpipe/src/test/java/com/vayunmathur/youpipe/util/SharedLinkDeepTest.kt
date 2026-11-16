package com.vayunmathur.youpipe.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.defaultBackStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith

/** Shared/deep-link parsing + phone default backstack (MainActivity helpers). */
@RunWith(AndroidJUnit4::class)
class SharedLinkDeepTest {
    @Test fun parseShared_watchYoutuBeShortsLiveEmbed() {
        val id = "dQw4w9WgXcQ"
        for (text in listOf(
            "https://www.youtube.com/watch?v=$id",
            "check this https://youtu.be/$id?t=10 wow",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id",
            "https://www.youtube.com/embed/$id",
        )) {
            val parsed = parseSharedVideoId(text)
            assertEquals(id, decodeVideoID(parsed!!), text)
            assertEquals("https://www.youtube.com/watch?v=$id", videoIDtoURL(parsed))
        }
    }
    @Test fun parseShared_noId_returnsNull() {
        assertNull(parseSharedVideoId("just some text"))
        assertNull(parseSharedVideoId("https://www.youtube.com/channel/UCabc"))
        assertNull(parseSharedVideoId("https://youtu.be/short"))
        assertNull(parseSharedVideoId(""))
    }
    @Test fun videoIdRoundTrip_andChannelUrls() {
        for (v in listOf(0L, 77L, -1L, Long.MAX_VALUE)) {
            assertEquals(v, encodeVideoID(decodeVideoID(v)))
            assertEquals(v, videoURLtoID(videoIDtoURL(v)))
        }
        assertEquals("UCx", channelURLtoID("https://www.youtube.com/channel/UCx"))
        assertEquals("https://www.youtube.com/channel/UCx", channelIDtoURL("UCx"))
        assertEquals("https://www.youtube.com/@h", channelIDtoURL("@h"))
    }
    @Test fun defaultBackStack_allKeys() {
        assertEquals(listOf(Route.Main(1)), defaultBackStack("subscriptions"))
        assertEquals(listOf(Route.Main(1), Route.SubscriptionVideosPage(null)), defaultBackStack("all_subscriptions"))
        assertEquals(listOf(Route.Main(2)), defaultBackStack("history"))
        assertEquals(listOf(Route.Main(3)), defaultBackStack("downloads"))
        assertEquals(listOf(Route.Main(3)), defaultBackStack("saved"))
        assertEquals(listOf(Route.Main(4)), defaultBackStack("settings"))
        assertEquals(listOf(Route.Main(0)), defaultBackStack(null))
        assertEquals(listOf(Route.Main(0)), defaultBackStack("garbage"))
    }
}
