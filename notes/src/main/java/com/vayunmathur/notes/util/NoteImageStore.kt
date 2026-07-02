package com.vayunmathur.notes.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * Stores note images as files under `filesDir/note_images`. A [com.vayunmathur.notes.data.NoteBlock.Image]
 * references an image by file name only, so the note database stays small and the
 * images survive app restarts.
 */
object NoteImageStore {
    private fun dir(context: Context): File =
        File(context.filesDir, "note_images").apply { mkdirs() }

    fun fileFor(context: Context, fileName: String): File = File(dir(context), fileName)

    /** Copies [uri] into the images dir and returns the new file name, or null on failure. */
    fun import(context: Context, uri: Uri): String? {
        val fileName = "img_${UUID.randomUUID()}.jpg"
        val dest = File(dir(context), fileName)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            fileName
        } catch (e: Exception) {
            dest.delete()
            null
        }
    }

    fun delete(context: Context, fileName: String) {
        try {
            fileFor(context, fileName).delete()
        } catch (_: Exception) {
        }
    }
}
