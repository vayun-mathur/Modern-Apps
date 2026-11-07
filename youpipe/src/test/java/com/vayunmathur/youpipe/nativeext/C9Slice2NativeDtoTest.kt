package com.vayunmathur.youpipe.nativeext

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Extractor DTO JSON contracts: snake_case fields, defaults, sealed search items. */
class C9Slice2NativeDtoTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test fun channelInfo_decodes() {
        val info = json.decodeFromString<ChannelInfo>(
            """{"id":"UC123","url":"https://youtube.com/@x","name":"Chan","subscriber_count":4200,"verified":true,"next_page_token":"tok","items":[],"errors":["e1"]}"""
        )
        assertEquals("UC123", info.id)
        assertEquals(4200L, info.subscriberCount)
        assertTrue(info.verified)
        assertEquals("tok", info.nextPageToken)
        assertEquals(listOf("e1"), info.errors)
    }

    @Test fun channelInfo_defaults() {
        val info = json.decodeFromString<ChannelInfo>("""{"id":"UC1","url":"u","name":"n"}""")
        assertEquals(-1L, info.subscriberCount)
        assertNull(info.description)
        assertTrue(info.items.isEmpty())
    }

    @Test fun audioStream_decodes() {
        val s = json.decodeFromString<AudioStream>(
            """{"url":"https://x/a","itag":140,"mime_type":"audio/mp4","bitrate":128000,"sample_rate":44100,"channels":2,"track_id":"en.4","is_drc":true}"""
        )
        assertEquals(140L, s.itag)
        assertEquals("audio/mp4", s.mimeType)
        assertEquals("en.4", s.trackId)
        assertTrue(s.isDrc)
        val d = json.decodeFromString<AudioStream>("""{"url":"u"}""")
        assertEquals(2L, d.channels)
        assertEquals("", d.mimeType)
    }

    @Test fun commentAndPage_decode() {
        val page = json.decodeFromString<CommentsPage>(
            """{"comments":[{"id":"c1","text":"hi","author_name":"Ann","like_count":10,"reply_count":2,"is_pinned":true}],"next_page_token":"n"}"""
        )
        assertEquals(1, page.comments.size)
        assertEquals("Ann", page.comments[0].authorName)
        assertTrue(page.comments[0].isPinned)
        assertEquals("n", page.nextPageToken)
    }

    @Test fun itemsPage_andSearchResult() {
        val page = json.decodeFromString<ItemsPage>("""{"items":[],"next_page_token":null}""")
        assertTrue(page.items.isEmpty())
        val result = json.decodeFromString<SearchResult>(
            """{"items":[],"is_corrected_search":true,"search_suggestion":"did you mean x"}"""
        )
        assertTrue(result.isCorrectedSearch)
        assertEquals("did you mean x", result.searchSuggestion)
    }

    @Test fun searchItem_streamVariant() {
        val item = json.decodeFromString<SearchItem>(
            """{"type":"STREAM","url":"https://youtube.com/watch?v=abc","name":"Video","duration_seconds":95,"uploader_name":"Chan","view_count":1000}"""
        )
        val stream = assertIs<SearchItem.Stream>(item)
        assertEquals(95L, stream.durationSeconds)
        assertEquals("Chan", stream.uploaderName)
    }

    @Test fun streamInfo_defaults() {
        val info = json.decodeFromString<StreamInfo>("""{"id":"abc","url":"u","name":"n"}""")
        assertEquals(-1L, info.viewCount)
        assertEquals(0L, info.durationSeconds)
        assertTrue(info.thumbnails.isEmpty())
    }
}
