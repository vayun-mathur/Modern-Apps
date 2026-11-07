package com.vayunmathur.youpipe.nativeext

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Slice 2/3 pass 3: extractor JSON models — snake_case payloads round-trip with every field intact. */
class NativeJsonPass3Test {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun searchResult_decodesEveryItemKindFromWireJson() {
        val payload = """
            {"items":[
              {"type":"STREAM","url":"https://youtu.be/v1","name":"A Stream","duration_seconds":372,
               "uploader_name":"Chan","uploader_url":"https://youtube.com/@c","uploader_verified":true,
               "textual_upload_date":"3 days ago","view_count":12345,
               "thumbnails":[{"url":"https://i/1.jpg","width":120,"height":90}],"is_live":false,"is_short":true},
              {"type":"CHANNEL","url":"https://youtube.com/@c","name":"Chan","description":"about",
               "subscriber_count":987,"stream_count":42,"verified":true,
               "thumbnails":[{"url":"https://i/a.jpg","width":88,"height":88}]},
              {"type":"PLAYLIST","url":"https://youtube.com/playlist?list=PL1","name":"Mix",
               "uploader_name":"Chan","stream_count":7,"thumbnails":[]}
            ],"next_page_token":"tok-2","search_suggestion":"did you mean","is_corrected_search":true,"errors":["one bad item"]}
        """.trimIndent()
        val result = json.decodeFromString<SearchResult>(payload)
        assertEquals("tok-2", result.nextPageToken)
        assertEquals("did you mean", result.searchSuggestion)
        assertTrue(result.isCorrectedSearch)
        assertEquals(listOf("one bad item"), result.errors)
        val stream = result.items[0] as SearchItem.Stream
        assertEquals(372L, stream.durationSeconds)
        assertEquals("Chan", stream.uploaderName)
        assertTrue(stream.uploaderVerified)
        assertEquals(12345L, stream.viewCount)
        assertTrue(stream.isShort)
        assertFalse(stream.isLive)
        assertEquals(120L, stream.thumbnails.single().width)
        val channel = result.items[1] as SearchItem.Channel
        assertEquals(987L, channel.subscriberCount)
        assertEquals(42L, channel.streamCount)
        assertEquals("about", channel.description)
        val playlist = result.items[2] as SearchItem.Playlist
        assertEquals(7L, playlist.streamCount)
        assertEquals("Chan", playlist.uploaderName)
        // Re-encode and decode again: no field is lost in either direction.
        assertEquals(result, json.decodeFromString<SearchResult>(json.encodeToString<SearchResult>(result)))
    }

    @Test
    fun searchResult_defaultsForMissingFields() {
        val result = json.decodeFromString<SearchResult>("""{"items":[]}""")
        assertTrue(result.items.isEmpty())
        assertNull(result.nextPageToken)
        assertFalse(result.isCorrectedSearch)
        val stream = SearchItem.Stream(url = "u", name = "n")
        assertEquals(UNKNOWN_COUNT, stream.viewCount)
        assertNull(stream.durationSeconds)
        assertEquals(-1L, UNKNOWN_COUNT)
    }

    @Test
    fun streamInfo_decodesNestedStreams() {
        val payload = """
            {"id":"abc123","url":"https://youtu.be/abc123","name":"Title","kind":"LIVE",
             "duration_seconds":60,"view_count":10,"like_count":2,"uploader_name":"Up",
             "video_streams":[{"url":"v","itag":22,"mime_type":"video/mp4","width":1280,"height":720,"fps":30,"bitrate":2000}],
             "audio_streams":[{"url":"a","itag":140,"mime_type":"audio/mp4","bitrate":128,"track_id":"en.4","track_name":"English"}],
             "video_only_streams":[{"url":"vo","itag":137,"video_only":true}],
             "sabr_only":true,"server_abr_streaming_url":"sabr://x","tags":["music"]}
        """.trimIndent()
        val info = json.decodeFromString<StreamInfo>(payload)
        assertEquals(StreamKind.LIVE, info.kind)
        assertTrue(info.sabrOnly)
        assertEquals("sabr://x", info.serverAbrStreamingUrl)
        assertEquals(listOf("music"), info.tags)
        assertEquals(1280L, info.videoStreams.single().width)
        assertEquals("en.4", info.audioStreams.single().trackId)
        assertTrue(info.videoOnlyStreams.single().videoOnly)
        assertEquals(info, json.decodeFromString<StreamInfo>(json.encodeToString<StreamInfo>(info)))
        assertEquals(StreamKind.POST_LIVE, json.decodeFromString<StreamKind>("\"POST_LIVE\""))
    }

    @Test
    fun channelPlaylistAndCommentsPages_decode() {
        val channel = json.decodeFromString<ChannelInfo>(
            """{"id":"UC1","url":"u","name":"Chan","subscriber_count":5,"verified":true,
                "items":[{"type":"STREAM","url":"s","name":"Vid"}],"next_page_token":"n"}""",
        )
        assertEquals(5L, channel.subscriberCount)
        assertEquals("Vid", (channel.items.single() as SearchItem.Stream).name)
        val playlist = json.decodeFromString<PlaylistInfo>(
            """{"id":"PL1","url":"u","name":"List","stream_count":3,"uploader_name":"Chan"}""",
        )
        assertEquals(3L, playlist.streamCount)
        val comments = json.decodeFromString<CommentsPage>(
            """{"comments":[{"id":"c1","text":"great","author_name":"Fan","like_count":9,"is_pinned":true}],"next_page_token":"ct"}""",
        )
        assertTrue(comments.comments.single().isPinned)
        assertEquals(9L, comments.comments.single().likeCount)
        val page = json.decodeFromString<ItemsPage>("""{"items":[],"next_page_token":"x","errors":[]}""")
        assertEquals("x", page.nextPageToken)
    }
}
