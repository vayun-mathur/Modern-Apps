package com.vayunmathur.youpipe.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Static SponsorBlock/DeArrow shard parsing (same shapes as the server's sb_shards.rs). */
class SbShardTest {

    @Test
    fun prefix_is_the_first_four_hex_digits_of_sha256() {
        // sha256("dQw4w9WgXcQ") starts 5f6b (computed independently with sha256sum).
        assertEquals(SHA_PREFIX, sbShardPrefix("dQw4w9WgXcQ"))
        assertEquals(4, sbShardPrefix("x").length)
    }

    @Test
    fun segments_keep_the_endpoints_default_action_types_for_the_right_video() {
        val body = """[
            {"videoID":"other","hash":"h","segments":[{"UUID":"o","segment":[1,2],"category":"sponsor","actionType":"skip"}]},
            {"videoID":"dQw4w9WgXcQ","hash":"h","segments":[
                {"UUID":"a","segment":[10.5,20],"category":"sponsor","actionType":"skip","votes":3,"locked":0},
                {"UUID":"b","segment":[30,31],"category":"music_offtopic","actionType":"mute"},
                {"UUID":"c","segment":[40,40],"category":"poi_highlight","actionType":"poi"},
                {"UUID":"d","segment":[0,600],"category":"exclusive_access","actionType":"full"}
            ]}
        ]"""
        val segs = segmentsFromShard(body, "dQw4w9WgXcQ")
        assertEquals(listOf("a", "b"), segs.map { it.uuid })
        assertEquals(10_500L, segs[0].start)
        assertTrue(segmentsFromShard(body, "absent").isEmpty())
        assertTrue(segmentsFromShard("[]", "dQw4w9WgXcQ").isEmpty())
    }

    @Test
    fun branding_absent_from_its_shard_is_empty() {
        val body = """{"dQw4w9WgXcQ":{"titles":[{"title":"Better","original":false,"votes":2,"locked":false,"UUID":"t"}],
            "thumbnails":[],"randomTime":0.25,"videoDuration":212.0}}"""
        assertEquals("Better", brandingFromShard(body, "dQw4w9WgXcQ").trustedTitle())
        val none = brandingFromShard(body, "absent")
        assertTrue(none.titles.isEmpty() && none.thumbnails.isEmpty())
        assertEquals(null, brandingFromShard("{}", "x").trustedTitle())
    }

    private companion object {
        const val SHA_PREFIX = "5f6b"
    }
}
