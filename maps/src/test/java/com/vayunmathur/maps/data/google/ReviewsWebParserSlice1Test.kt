package com.vayunmathur.maps.data.google

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): [ReviewsWebParser] — the DOM-scraped reviews JSON our own
 * [WebReviewsFetcher] builds, parsed back into [GoogleReview]s.
 *
 * The parser is best-effort by contract (a malformed blob is an empty list,
 * never a throw), so the tests pin the hostile inputs as well as the fields.
 */
class ReviewsWebParserSlice1Test {

    @Test
    fun `a full card parses every field`() {
        val json = """[{
            "rid":"r1","r":5,"a":"Ada Lovelace","d":"2 weeks ago","t":"Lovely spot.",
            "av":"https://lh3.googleusercontent.com/a/avatar",
            "p":["https://lh3.googleusercontent.com/p/1","https://lh3.googleusercontent.com/p/2"]
        }]"""
        val reviews = ReviewsWebParser.parse(json)
        assertEquals(1, reviews.size)
        val r = reviews.single()
        assertEquals("Ada Lovelace", r.author)
        assertEquals(5, r.rating)
        assertEquals("2 weeks ago", r.relativeTime)
        assertEquals("Lovely spot.", r.text)
        assertEquals("https://lh3.googleusercontent.com/a/avatar", r.authorPhoto)
        assertEquals(
            listOf(
                "https://lh3.googleusercontent.com/p/1",
                "https://lh3.googleusercontent.com/p/2",
            ),
            r.photos,
        )
    }

    @Test
    fun `a card without an author is dropped`() {
        val json = """[{"rid":"r1","r":4,"a":"  ","t":"Ghost review"},{"rid":"r2","r":4,"t":"Nameless"}]"""
        assertTrue(ReviewsWebParser.parse(json).isEmpty())
    }

    @Test
    fun `ratings clamp to the 0 to 5 range`() {
        val json = """[{"a":"High","r":9},{"a":"Low","r":-3},{"a":"Missing"}]"""
        val ratings = ReviewsWebParser.parse(json).map { it.rating }
        assertEquals(listOf(5, 0, 0), ratings)
    }

    @Test
    fun `non-http photo and avatar urls are ignored`() {
        val json = """[{
            "a":"Picky","r":3,
            "av":"data:image/png;base64,xx",
            "p":["https://lh3.googleusercontent.com/p/1","https://lh3.googleusercontent.com/p/1","blob:internal","/relative/path"]
        }]"""
        val r = ReviewsWebParser.parse(json).single()
        assertNull(r.authorPhoto)
        // De-duplicated, http-only.
        assertEquals(listOf("https://lh3.googleusercontent.com/p/1"), r.photos)
    }

    @Test
    fun `blank date and text become null`() {
        val json = """[{"a":"Terse","r":4,"d":"  ","t":""}]"""
        val r = ReviewsWebParser.parse(json).single()
        assertNull(r.relativeTime)
        assertNull(r.text)
    }

    @Test
    fun `rating-only reviews keep a null text`() {
        val json = """[{"a":"Stars","r":5}]"""
        val r = ReviewsWebParser.parse(json).single()
        assertEquals(5, r.rating)
        assertNull(r.text)
    }

    @Test
    fun `malformed blobs parse to empty, never throw`() {
        assertTrue(ReviewsWebParser.parse("").isEmpty())
        assertTrue(ReviewsWebParser.parse("not json").isEmpty())
        assertTrue(ReviewsWebParser.parse("""{"not":"an array"}""").isEmpty())
        assertTrue(ReviewsWebParser.parse("[]").isEmpty())
    }
}
