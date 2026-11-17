package com.vayunmathur.flashcards.util
import com.vayunmathur.flashcards.platform.MediaStore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaRefTest {

    @Test
    fun findsReferencedNamesAcrossFields() {
        val md = "front ![](a.jpg)\u001fback ![alt](b.png) and again ![](a.jpg)"
        assertEquals(setOf("a.jpg", "b.png"), MediaStore.referenced(md))
    }

    @Test
    fun noImagesReturnsEmpty() {
        assertTrue(MediaStore.referenced("just plain [not an image](x)").isEmpty())
    }

    @Test
    fun trimsWhitespaceInNames() {
        assertEquals(setOf("pic.png"), MediaStore.referenced("![alt]( pic.png )"))
    }
}
