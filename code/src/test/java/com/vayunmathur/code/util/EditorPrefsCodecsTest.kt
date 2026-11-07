package com.vayunmathur.code.util

import kotlin.test.Test
import kotlin.test.assertEquals
import com.vayunmathur.code.syntax.Language
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Unit tests for the snippet/fold-state JSON codecs (org.json runs for real under Robolectric). */
@RunWith(RobolectricTestRunner::class)
class EditorPrefsCodecsTest {

    @Test
    fun snippetRoundTripKeepsLanguage() {
        val snippets = listOf(
            UserSnippet("log", "console.log($0)", null),
            UserSnippet("pylog", "print()", Language.PYTHON.name),
        )
        val decoded = decodeSnippets(encodeSnippets(snippets))
        assertEquals(snippets, decoded)
    }

    @Test
    fun emptySnippetListRoundTrips() {
        assertEquals(emptyList(), decodeSnippets(encodeSnippets(emptyList())))
    }

    @Test
    fun decodeSnippetsHandlesNullBlankAndGarbage() {
        assertEquals(emptyList(), decodeSnippets(null))
        assertEquals(emptyList(), decodeSnippets(""))
        assertEquals(emptyList(), decodeSnippets("not json {{"))
    }

    @Test
    fun foldStateRoundTrip() {
        val state = mapOf("/a.kt" to listOf(0, 5), "/b.kt" to listOf(3))
        assertEquals(state, decodeFoldState(encodeFoldState(state)))
    }

    @Test
    fun emptyFoldStateRoundTrips() {
        assertEquals(emptyMap(), decodeFoldState(encodeFoldState(emptyMap())))
    }

    @Test
    fun decodeFoldStateHandlesNullBlankAndGarbage() {
        assertEquals(emptyMap(), decodeFoldState(null))
        assertEquals(emptyMap(), decodeFoldState(""))
        assertEquals(emptyMap(), decodeFoldState("{{{ nope"))
    }

    @Test
    fun snippetWithoutLanguageHasNullLanguageId() {
        val decoded = decodeSnippets(encodeSnippets(listOf(UserSnippet("t", "body"))))
        assertEquals(1, decoded.size)
        assertEquals("t", decoded[0].trigger)
        assertEquals("body", decoded[0].template)
        assertEquals(null, decoded[0].languageId)
    }

    @Test
    fun snippetTemplateWithCaretTokenSurvives() {
        val decoded = decodeSnippets(encodeSnippets(listOf(UserSnippet("log", "f($0)"))))
        assertEquals("f($0)", decoded.single().template)
    }
}
