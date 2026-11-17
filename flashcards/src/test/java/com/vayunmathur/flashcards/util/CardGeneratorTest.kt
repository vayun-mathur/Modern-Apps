package com.vayunmathur.flashcards.util
import com.vayunmathur.flashcards.data.CardGenerator

import com.vayunmathur.flashcards.data.CardTemplate
import com.vayunmathur.flashcards.data.NoteType
import com.vayunmathur.flashcards.data.NoteTypeField
import com.vayunmathur.flashcards.data.NoteTypeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class CardGeneratorTest {

    private val basicFields = listOf(
        NoteTypeField(noteTypeId = 1, ord = 0, name = "Front"),
        NoteTypeField(noteTypeId = 1, ord = 1, name = "Back"),
    )
    private val basicType = NoteType(id = 1, name = "Basic", type = NoteTypeKind.STANDARD)
    private val basicTemplates = listOf(
        CardTemplate(noteTypeId = 1, ord = 0, name = "Card 1", qfmt = "{{Front}}", afmt = "{{Back}}"),
    )

    private val reversedType = NoteType(id = 2, name = "Basic (and reversed card)", type = NoteTypeKind.STANDARD)
    private val reversedTemplates = listOf(
        CardTemplate(noteTypeId = 2, ord = 0, name = "Card 1", qfmt = "{{Front}}", afmt = "{{Back}}"),
        CardTemplate(noteTypeId = 2, ord = 1, name = "Card 2", qfmt = "{{Back}}", afmt = "{{Front}}"),
    )

    private val clozeType = NoteType(id = 3, name = "Cloze", type = NoteTypeKind.CLOZE)
    private val clozeFields = listOf(
        NoteTypeField(noteTypeId = 3, ord = 0, name = "Text"),
        NoteTypeField(noteTypeId = 3, ord = 1, name = "Back Extra"),
    )
    private val clozeTemplates = listOf(
        CardTemplate(noteTypeId = 3, ord = 0, name = "Cloze", qfmt = "{{cloze:Text}}", afmt = "{{cloze:Text}}"),
    )

    @Test
    fun basicGeneratesOneCard() {
        val ords = CardGenerator.requiredOrds(basicType, basicTemplates, basicFields, "hi\u001fthere")
        assertEquals(setOf(0), ords)
    }

    @Test
    fun reversedGeneratesTwoCards() {
        val ords = CardGenerator.requiredOrds(reversedType, reversedTemplates, basicFields, "hi\u001fthere")
        assertEquals(setOf(0, 1), ords)
    }

    @Test
    fun reversedSkipsCardWithEmptyFront() {
        // Back empty → Card 2 (front = Back) is blank and skipped.
        val ords = CardGenerator.requiredOrds(reversedType, reversedTemplates, basicFields, "hi\u001f")
        assertEquals(setOf(0), ords)
    }

    @Test
    fun clozeFindsOrds() {
        val ords = CardGenerator.findClozeOrds("The {{c1::a}} and {{c2::b}} and {{c3::c}}")
        assertEquals(setOf(0, 1, 2), ords)
    }

    @Test
    fun clozeHandlesGaps() {
        val ords = CardGenerator.findClozeOrds("Only {{c1::a}} and {{c3::c}}")
        assertEquals(setOf(0, 2), ords)
    }

    @Test
    fun clozeRequiredOrdsUsesClozeNumbers() {
        val ords = CardGenerator.requiredOrds(clozeType, clozeTemplates, clozeFields, "{{c1::x}} {{c2::y}}\u001f")
        assertEquals(setOf(0, 1), ords)
    }
}
