package com.vayunmathur.flashcards.util

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * App-private storage for card images. Images are referenced from note fields as
 * markdown `![alt](filename)` and stored as files under `filesDir/media/<name>`.
 * Pure file plumbing (no scheduling or DB) so it is easy to reason about.
 */
class MediaStore(context: Context) {

    private val dir: File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Resolves a bare media [name] to its file (may not exist). */
    fun resolve(name: String): File = File(dir, safeName(name))

    /**
     * Reduces [name] to a bare filename inside [dir]. Names come from
     * untrusted sources (an .apkg's media manifest, `![](…)` references in
     * imported note fields), so a name like `../../databases/flashcards-db`
     * must never escape the media directory and overwrite/read other
     * app-private files on import or export.
     */
    private fun safeName(name: String): String {
        val bare = name.substringAfterLast('/').substringAfterLast('\\').trim()
        return bare.takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "_"
    }

    /**
     * Copies the picked [uri] into media storage under a unique name and returns
     * the bare filename to embed as `![](name)`. Returns null if the copy fails.
     */
    fun import(context: Context, uri: Uri): String? {
        val ext = extensionFor(context, uri)
        val name = "img_${System.currentTimeMillis()}_${(0..9999).random()}$ext"
        val target = File(dir, name)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return null
            name
        }.getOrNull()
    }

    /** Copies raw [bytes] to media storage under [name] (renaming on collision). */
    fun writeBytes(name: String, bytes: ByteArray): String {
        val target = uniqueFile(name)
        target.writeBytes(bytes)
        return target.name
    }

    private fun uniqueFile(name: String): File {
        var candidate = resolve(name)
        if (!candidate.exists()) return candidate
        val bare = candidate.name
        val base = bare.substringBeforeLast('.', bare)
        val ext = bare.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base($i)$ext")
            i++
        }
        return candidate
    }

    /** Deletes any media files not present in [usedNames]. */
    fun garbageCollect(usedNames: Set<String>) {
        dir.listFiles()?.forEach { file ->
            if (file.name !in usedNames) file.delete()
        }
    }

    private fun extensionFor(context: Context, uri: Uri): String {
        val type = context.contentResolver.getType(uri)
        return when {
            type == "image/png" -> ".png"
            type == "image/webp" -> ".webp"
            type == "image/gif" -> ".gif"
            type == "image/jpeg" -> ".jpg"
            else -> ".jpg"
        }
    }

    companion object {
        private const val DIR = "media"

        private val imageRegex = Regex("""!\[[^\]]*]\(([^)]+)\)""")

        /** The set of media filenames referenced by an `![alt](name)` in [md]. */
        fun referenced(md: String): Set<String> =
            imageRegex.findAll(md).map { it.groupValues[1].trim() }.toSet()
    }
}
