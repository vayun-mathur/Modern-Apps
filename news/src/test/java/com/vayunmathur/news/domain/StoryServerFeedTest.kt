package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Server story fields: parsed when present, absent = singleton story (no client grouping). */
class StoryServerFeedTest {
    @Test fun parsesStoryFieldsAndIgnoresExtractedText() {
        val json = """
            {"articles":[
              {"title":"T one","url":"https://e/1","source":"bbc","published_at":"2026-11-23T10:00:00Z",
               "story_id":"abc","story_one_liner":"A summary.","story_title":"Canonical",
               "extracted_text":"MUST NOT BE CONSUMED"},
              {"title":"T two","url":"https://e/2","source":"dw","published_at":"2026-11-23T11:00:00Z"}
            ]}
        """.trimIndent()
        val parsed = ServerFeedParser.parse(json)
        assertEquals(2, parsed.size)
        assertEquals("abc", parsed[0].serverStoryId)
        assertEquals("A summary.", parsed[0].storyOneLiner)
        assertEquals("Canonical", parsed[0].serverStoryTitle)
        assertNull(parsed[1].serverStoryId)
        // No field on FeedArticle can carry extracted text; grouping uses story_id only.
        val stories = StoryGrouping.group(parsed)
        assertEquals(2, stories.size, "the article without story_id stays a singleton")
        assertEquals("srv:abc", stories.first { it.serverStoryId == "abc" }.id)
        assertEquals(1, stories.first { it.serverStoryId == null }.articles.size)
    }
}
