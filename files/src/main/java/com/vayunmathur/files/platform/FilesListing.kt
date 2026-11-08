package com.vayunmathur.files.platform

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.util.zip.ZipFile

/**
 * Stateless helpers behind [FilesViewModel]'s home screen and zip browsing, split out
 * so the ViewModel file stays under the FileLength limit. Pure functions where
 * possible; the MediaStore queries take a [Context] instead of reaching for one.
 */

private const val MEDIA_QUERY_LIMIT = 2000
private const val RECENT_ITEMS_LIMIT = 40

internal fun File.toBrowserItem() = FileBrowserItem(
    name = name,
    isDirectory = isDirectory,
    size = if (isFile) length() else null,
    realFile = this,
    zipInnerPath = null,
    key = absolutePath,
    lastModified = lastModified(),
)

internal fun queryCategoryItems(context: Context, category: FileCategory): List<FileBrowserItem> =
    when (category) {
        FileCategory.IMAGES -> queryMediaItems(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null, MEDIA_QUERY_LIMIT,
        )
        FileCategory.VIDEOS -> queryMediaItems(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null, MEDIA_QUERY_LIMIT,
        )
        FileCategory.AUDIO -> queryMediaItems(
            context,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, MEDIA_QUERY_LIMIT,
        )
        FileCategory.DOCUMENTS -> {
            val mimes = arrayOf(
                "application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain", "text/markdown", "text/csv", "application/rtf",
            )
            val selection = mimes.joinToString(" OR ") {
                "${MediaStore.Files.FileColumns.MIME_TYPE}=?"
            }
            queryMediaItems(
                context,
                MediaStore.Files.getContentUri("external"),
                selection,
                mimes,
                MEDIA_QUERY_LIMIT
            )
        }
        FileCategory.DOWNLOADS -> emptyList()
    }

internal fun queryRecentItems(context: Context): List<FileBrowserItem> =
    queryMediaItems(
        context,
        MediaStore.Files.getContentUri("external"),
        null,
        null,
        RECENT_ITEMS_LIMIT
    )

internal fun queryMediaItems(
    context: Context,
    uri: Uri,
    selection: String?,
    args: Array<String>?,
    limit: Int,
): List<FileBrowserItem> {
    val projection = arrayOf(MediaStore.MediaColumns.DATA)
    val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
    val out = mutableListOf<FileBrowserItem>()
    try {
        context.contentResolver.query(uri, projection, selection, args, sort)?.use { c ->
            val idx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (c.moveToNext() && out.size < limit) {
                val path = c.getString(idx) ?: continue
                val f = File(path)
                if (f.isFile) out.add(f.toBrowserItem())
            }
        }
    } catch (_: IllegalArgumentException) {
    }
    return out
}

internal fun loadBookmarkItems(prefs: android.content.SharedPreferences): List<FileBrowserItem> {
    val saved = prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()
    return saved.map { File(it) }
        .filter { it.exists() }
        .sortedBy { it.name.lowercase() }
        .map { it.toBrowserItem() }
}

/**
 * A plain file name from an untrusted display name, or null when there is none.
 *
 * Display names come from other apps (a share target's provider, a SAF caller),
 * so they are data, not paths: any directory part is dropped and "." / ".."
 * are rejected, keeping the result a single name inside whatever folder the
 * user actually chose.
 */
internal fun sanitizedFileName(rawName: String?): String? {
    val name = rawName?.trim()?.let { File(it).name } ?: return null
    return name.takeIf { it.isNotBlank() && it != "." && it != ".." }
}

/** A destination in [dir] named [name], suffixed with " (n)" if that already exists. */
internal fun uniqueDestination(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (candidate.exists()) {
        candidate = File(dir, "$base ($n)$ext")
        n++
    }
    return candidate
}

internal fun listRealDirItems(
    dir: File,
    showHidden: Boolean,
): Pair<List<FileBrowserItem>, List<FileBrowserItem>> {
    val all = dir.listFiles()?.toList() ?: emptyList()
    val visible = if (showHidden) all else all.filterNot { it.name.startsWith(".") }
    val items = visible.map { it.toBrowserItem() }
    return items.partition { it.isDirectory }
}

/** One zip entry reduced to what the listing needs: where it is and what kind it is. */
private data class ZipListingEntry(
    val fullInner: String,
    val remainder: String,
    val isDirectory: Boolean,
    val size: Long,
)

/** Folders found so far (keyed so duplicate entries share one row) plus file rows. */
private class ZipListingAccumulator(private val internalDir: String) {
    val dirs = mutableMapOf<String, FileBrowserItem>()
    val files = mutableMapOf<String, FileBrowserItem>()

    fun addDir(first: String) {
        if (dirs.containsKey(first)) return
        val fullInner = joinInner(first)
        dirs[first] = FileBrowserItem(
            name = first,
            isDirectory = true,
            size = null,
            realFile = null,
            zipInnerPath = fullInner,
            key = "zip:$fullInner",
        )
    }

    fun addEntry(entry: ZipListingEntry) {
        val slashIdx = entry.remainder.indexOf('/')
        if (slashIdx != -1) {
            val first = entry.remainder.substring(0, slashIdx)
            if (first.isNotEmpty()) addDir(first)
            return
        }
        if (entry.isDirectory) {
            addDir(entry.remainder)
        } else {
            files[entry.remainder] = FileBrowserItem(
                name = entry.remainder,
                isDirectory = false,
                size = entry.size.takeIf { it >= 0 },
                realFile = null,
                zipInnerPath = entry.fullInner,
                key = "zip:${entry.fullInner}",
            )
        }
    }

    private fun joinInner(first: String): String =
        if (internalDir.isEmpty()) first else "$internalDir/$first"
}

/** Reduces one raw zip entry name to a listing row, or null when it is not shown here. */
private fun classifyZipEntry(
    rawName: String,
    isDirectory: Boolean,
    size: Long,
    internalDir: String,
    prefix: String
): ZipListingEntry? {
    val normalized = rawName.trimEnd('/')
    if (normalized.isEmpty()) return null
    if (normalized == internalDir) return null
    if (internalDir.isNotEmpty() &&
        !rawName.startsWith(prefix) &&
        !normalized.startsWith(prefix)
    ) return null
    val remainder = if (prefix.isEmpty()) {
        normalized
    } else {
        if (normalized.length <= prefix.length) return null
        normalized.substring(prefix.length)
    }
    if (remainder.isEmpty()) return null
    val fullInner = if (internalDir.isEmpty()) remainder else "$internalDir/$remainder"
    return ZipListingEntry(fullInner, remainder, isDirectory, size)
}

internal fun listZipDirItems(
    zipFile: File,
    internalDir: String,
): Pair<List<FileBrowserItem>, List<FileBrowserItem>> {
    return try {
        ZipFile(zipFile).use { zf ->
            val prefix = if (internalDir.isEmpty()) "" else "$internalDir/"
            val acc = ZipListingAccumulator(internalDir)
            for (entry in zf.entries()) {
                classifyZipEntry(entry.name, entry.isDirectory, entry.size, internalDir, prefix)
                    ?.let { acc.addEntry(it) }
            }
            acc.dirs.values.toList() to acc.files.values.toList()
        }
    } catch (_: java.io.IOException) {
        emptyList<FileBrowserItem>() to emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList<FileBrowserItem>() to emptyList()
    }
}
