package com.vayunmathur.openassistant.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class A2uiBlocksTest {

    private val block = "```a2ui\n{\"components\":[]}\n```"

    @Test
    fun splitsProseFromBlock() {
        val split = splitA2ui("Here you go:\n$block", streaming = false)
        assertEquals("Here you go:", split.visible)
        assertEquals("{\"components\":[]}", split.json)
    }

    @Test
    fun plainRepliesAndOtherFencesPassThrough() {
        assertEquals(A2uiSplit("Hello", null), splitA2ui("Hello"))
        val code = "```kotlin\nval x = 1\n```"
        assertEquals(A2uiSplit(code, null), splitA2ui(code, streaming = false))
    }

    @Test
    fun blockOnlyReplyHasNoVisibleText() {
        assertEquals(A2uiSplit("", "{\"components\":[]}"), splitA2ui(block, streaming = false))
    }

    @Test
    fun hidesUnclosedBlockAndPartialFenceWhileStreaming() {
        assertEquals(A2uiSplit("Sure", null), splitA2ui("Sure\n```a2ui\n{\"compo"))
        assertEquals("Sure", splitA2ui("Sure ```a2").visible)
        assertEquals("Sure ``", splitA2ui("Sure ``", streaming = false).visible)
        assertEquals("Run `ls` now", splitA2ui("Run `ls` now").visible)
    }

    @Test
    fun firstBlockWinsAndAllAreHidden() {
        val split = splitA2ui("a ```a2ui\n[1]\n``` b ```a2ui\n[2]\n``` c", streaming = false)
        assertEquals("a  b  c", split.visible)
        assertEquals("[1]", split.json)
    }

    @Test
    fun restoredBlockRoundTrips() {
        val restored = withA2uiBlock("Here you go:", "{\"components\":[]}")
        assertEquals(A2uiSplit("Here you go:", "{\"components\":[]}"), splitA2ui(restored, streaming = false))
        assertEquals("text", withA2uiBlock("text", null))
        assertNull(splitA2ui(withA2uiBlock("", null)).json)
    }
}
