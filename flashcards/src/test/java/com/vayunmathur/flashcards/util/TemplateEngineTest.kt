package com.vayunmathur.flashcards.util
import com.vayunmathur.flashcards.domain.TemplateEngine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateEngineTest {

    @Test
    fun substitutesFieldsFrontAndBack() {
        val (front, back) = TemplateEngine.render(
            qfmt = "{{Front}}",
            afmt = "{{FrontSide}}\n\n---\n\n{{Back}}",
            fields = mapOf("Front" to "capital of France", "Back" to "Paris"),
        )
        assertEquals("capital of France", front)
        assertEquals("capital of France\n\n---\n\nParis", back)
    }

    @Test
    fun unknownFieldRendersEmpty() {
        val (front, _) = TemplateEngine.render(
            qfmt = "{{Front}} {{Missing}}",
            afmt = "{{Back}}",
            fields = mapOf("Front" to "hi", "Back" to "x"),
        )
        assertEquals("hi", front.trim())
    }

    @Test
    fun positiveConditionalKeepsWhenPresent() {
        val (front, _) = TemplateEngine.render(
            qfmt = "{{Front}}{{#Extra}} ({{Extra}}){{/Extra}}",
            afmt = "{{Back}}",
            fields = mapOf("Front" to "word", "Extra" to "note", "Back" to "b"),
        )
        assertEquals("word (note)", front)
    }

    @Test
    fun positiveConditionalDropsWhenEmpty() {
        val (front, _) = TemplateEngine.render(
            qfmt = "{{Front}}{{#Extra}} ({{Extra}}){{/Extra}}",
            afmt = "{{Back}}",
            fields = mapOf("Front" to "word", "Extra" to "", "Back" to "b"),
        )
        assertEquals("word", front)
    }

    @Test
    fun negativeConditionalKeepsWhenEmpty() {
        val (front, _) = TemplateEngine.render(
            qfmt = "{{^Extra}}no extra{{/Extra}}",
            afmt = "{{Back}}",
            fields = mapOf("Extra" to "", "Back" to "b"),
        )
        assertEquals("no extra", front)
    }

    @Test
    fun nestedConditionals() {
        val (front, _) = TemplateEngine.render(
            qfmt = "{{#A}}A{{#B}}B{{/B}}{{/A}}",
            afmt = "{{Back}}",
            fields = mapOf("A" to "1", "B" to "1", "Back" to "b"),
        )
        assertEquals("AB", front)
    }

    @Test
    fun clozeFrontMasksActiveAndRevealsOthers() {
        val fields = mapOf("Text" to "The {{c1::sun}} is a {{c2::star}}")
        val (front, back) = TemplateEngine.render(
            qfmt = "{{cloze:Text}}",
            afmt = "{{cloze:Text}}",
            fields = fields,
            clozeOrd = 0,
        )
        assertEquals("The [\u2026] is a star", front)
        assertEquals("The **sun** is a star", back)
    }

    @Test
    fun clozeSecondOrd() {
        val fields = mapOf("Text" to "The {{c1::sun}} is a {{c2::star}}")
        val (front, back) = TemplateEngine.render(
            qfmt = "{{cloze:Text}}",
            afmt = "{{cloze:Text}}",
            fields = fields,
            clozeOrd = 1,
        )
        assertEquals("The sun is a [\u2026]", front)
        assertEquals("The sun is a **star**", back)
    }

    @Test
    fun clozeHintShownOnFront() {
        val fields = mapOf("Text" to "Capital: {{c1::Paris::city}}")
        val (front, _) = TemplateEngine.render(
            qfmt = "{{cloze:Text}}",
            afmt = "{{cloze:Text}}",
            fields = fields,
            clozeOrd = 0,
        )
        assertEquals("Capital: [city]", front)
    }

    @Test
    fun frontSideEmbeddedInBack() {
        val (_, back) = TemplateEngine.render(
            qfmt = "{{Front}}",
            afmt = "{{FrontSide}} => {{Back}}",
            fields = mapOf("Front" to "Q", "Back" to "A"),
        )
        assertTrue(back.startsWith("Q => A"))
    }

    @Test
    fun typeFieldDetection() {
        assertEquals("Back", TemplateEngine.typeField("{{Front}}\n{{type:Back}}"))
        assertEquals(null, TemplateEngine.typeField("{{Front}}"))
    }

    @Test
    fun typeMarkerBlankOnFrontAnswerOnBack() {
        val (front, back) = TemplateEngine.render(
            qfmt = "{{Front}} {{type:Back}}",
            afmt = "{{FrontSide}}\n{{type:Back}}",
            fields = mapOf("Front" to "capital?", "Back" to "Paris"),
        )
        assertEquals("capital?", front.trim())
        assertTrue(back.contains("Paris"), "answer should appear on the back: $back")
    }
}
