package com.vayunmathur.notes.data

import com.vayunmathur.library.util.SerializedStroke
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * One piece of a note's body. A note is an ordered list of blocks so it can mix
 * typed text, inserted images, and handwriting like OneNote / Apple Notes.
 *
 * Legacy text-only notes have no stored blocks; they are read back as a single
 * [Text] block built from [Note.content] (see [Note.blockList]).
 */
@Serializable
sealed interface NoteBlock {
    val id: String

    @Serializable
    @SerialName("text")
    data class Text(
        val markdown: String = "",
        override val id: String = randomBlockId(),
    ) : NoteBlock

    /** [fileName] names an image file in the note-images dir (see [com.vayunmathur.notes.util.NoteImageStore]). */
    @Serializable
    @SerialName("image")
    data class Image(
        val fileName: String,
        /** Display width as a fraction of the editor width, so images can be resized. */
        val widthFraction: Float = 1f,
        override val id: String = randomBlockId(),
    ) : NoteBlock

    /** Handwriting strokes drawn on a canvas [heightDp] tall. */
    @Serializable
    @SerialName("ink")
    data class Ink(
        val strokes: List<SerializedStroke> = emptyList(),
        val heightDp: Int = 300,
        override val id: String = randomBlockId(),
    ) : NoteBlock
}

fun randomBlockId(): String = UUID.randomUUID().toString()

/**
 * The full body of a note: an ordered list of inline [blocks]. Serialized to the
 * [Note.blocks] JSON column.
 */
@Serializable
data class NoteBody(
    val blocks: List<NoteBlock> = emptyList(),
)

// ignoreUnknownKeys lets us drop any legacy fields (e.g. an old "floating" list
// from earlier builds) so older notes still parse instead of crashing.
private val blockJson = Json { ignoreUnknownKeys = true }

/** The note body: parses stored [Note.blocks], or falls back to a single text block. */
fun Note.body(): NoteBody {
    val json = blocks
    if (json.isNullOrBlank()) return NoteBody(blocks = listOf(NoteBlock.Text(content)))
    return try {
        blockJson.decodeFromString<NoteBody>(json)
    } catch (e: Exception) {
        NoteBody(blocks = listOf(NoteBlock.Text(content)))
    }
}

/**
 * Returns a copy storing [newBody], keeping [Note.content] in sync with the text
 * blocks so search, list previews, sharing, and the assistant intents keep working
 * on plain text.
 */
fun Note.withBody(newBody: NoteBody): Note = copy(
    blocks = blockJson.encodeToString(newBody),
    content = newBody.blocks.filterIsInstance<NoteBlock.Text>()
        .joinToString("\n\n") { it.markdown }
        .trim(),
)
