package com.vayunmathur.music.wear.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class WearMusicProtocolTest {

    @Test
    fun goldenPageJson() {
        val snapshot = WearPageSnapshot(
            page = WearPageId(WearPageKey.HOME),
            generation = 7L,
            tracks = listOf(WearTrack(3L, "Blue", "Artist", "Album", 1_000L)),
            nextCursor = "30",
            totalCount = 31,
        )
        assertEquals(
            """{"page":{"key":"HOME"},"generation":7,"tracks":[{"id":3,"title":"Blue","artist":"Artist","album":"Album","durationMs":1000}],"nextCursor":"30","totalCount":31}""",
            WearMusicProtocol.json.encodeToString(snapshot),
        )
    }

    @Test
    fun goldenOpJson() {
        val op = WearMusicOp(WearOpKind.PLAY_TRACKS, trackIds = listOf(3L, 4L), startIndex = 1)
        assertEquals(
            """{"kind":"PLAY_TRACKS","trackIds":[3,4],"startIndex":1}""",
            WearMusicProtocol.json.encodeToString(op),
        )
    }

    @Test
    fun goldenPlaybackJson() {
        val state = WearPlaybackState(trackId = 3L, title = "Blue", isPlaying = true, shuffle = true, repeatMode = 2)
        assertEquals(
            """{"trackId":3,"title":"Blue","isPlaying":true,"shuffle":true,"repeatMode":2}""",
            WearMusicProtocol.json.encodeToString(state),
        )
    }

    @Test
    fun paginationWalksAllItemsOnce() {
        val items = (1..75).toList()
        val seen = mutableListOf<Int>()
        var cursor = ""
        var pages = 0
        do {
            val (slice, next) = paginateWearPage(items, cursor, pageSize = 30)
            seen += slice
            cursor = next
            pages++
        } while (cursor.isNotEmpty())
        assertEquals(items, seen)
        assertEquals(3, pages)
    }

    @Test
    fun paginationMalformedCursorRestarts() {
        val (slice, _) = paginateWearPage((1..10).toList(), "junk", pageSize = 4)
        assertEquals(listOf(1, 2, 3, 4), slice)
    }

    @Test
    fun playbackReducerReflectsPhoneReportOnly() {
        val before = WearPlaybackState(title = "Old", isPlaying = false)
        val reported = WearPlaybackState(title = "New", isPlaying = true, positionMs = 5L)
        val after = reducePlayback(before, WearMusicOp(WearOpKind.TOGGLE_PLAY_PAUSE), reported)
        assertEquals(reported, after)
        assertTrue(after.isPlaying)
    }

    @Test
    fun repeatCycleMatchesPhoneOrder() {
        assertEquals(2, nextWearRepeatMode(0))
        assertEquals(1, nextWearRepeatMode(2))
        assertEquals(0, nextWearRepeatMode(1))
    }

    @Test
    fun pageRoundTrip() {
        val snapshot = WearPageSnapshot(
            WearPageId(WearPageKey.SEARCH, query = "blue"),
            tracks = listOf(WearTrack(1L, "Blue Suede")),
            totalCount = 1,
        )
        assertEquals(
            snapshot,
            WearMusicProtocol.json.decodeFromString<WearPageSnapshot>(WearMusicProtocol.json.encodeToString(snapshot)),
        )
    }
}
