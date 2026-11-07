package com.vayunmathur.contacts.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Direct coverage for the gender section's display-text mapping. */
class GenderDisplayTextTest {
    private val options = listOf("Female", "Male", "Other")

    @Test
    fun knownCodesMapToLabels() {
        assertEquals("Female", genderDisplayText("F", "", options))
        assertEquals("Male", genderDisplayText("M", "", options))
        assertEquals("Other", genderDisplayText("O", "", options))
    }

    @Test
    fun otherWithFreeTextAppendsIt() {
        assertEquals("Other (Agender)", genderDisplayText("O", " Agender ", options))
        assertEquals("Other", genderDisplayText("O", "   ", options))
        // free text is ignored for non-Other codes
        assertEquals("Female", genderDisplayText("F", "ignored", options))
    }

    @Test
    fun unknownOrBlankCodesFallBackToText() {
        assertEquals("custom", genderDisplayText("X", " custom ", options))
        assertEquals("", genderDisplayText("", "", options))
        assertEquals("", genderDisplayText("U", "", options))
    }

    @Test
    fun missingOptionsFallBackToText() {
        assertEquals("note", genderDisplayText("F", "note", emptyList()))
        assertEquals("", genderDisplayText("F", "", emptyList()))
        assertEquals("note", genderDisplayText("O", "note", listOf("Only")))
    }
}
