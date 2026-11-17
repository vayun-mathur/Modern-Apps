package com.vayunmathur.library.image.fetchers

import android.content.Context
import android.net.Uri
import java.io.File

/** Fetcher reading image files from local storage. */
class FileFetcher : Fetcher {
    override suspend fun fetch(data: Any?, context: Context): FetchResult? {
        val file = resolveFile(data) ?: return null
        return readFileBytes(file)
    }

    private fun resolveFile(data: Any?): File? =
        when (data) {
            is File -> data.takeIf { it.exists() }
            is Uri -> resolveUriFile(data)
            is String -> resolvePathString(data)
            else -> null
        }

    private fun resolveUriFile(data: Uri): File? {
        if (data.scheme != FILE_SCHEME) return null
        val path = data.path ?: return null
        return File(path).takeIf { it.exists() }
    }

    private fun resolvePathString(data: String): File? {
        val path = if (data.startsWith(FILE_URI_PREFIX)) {
            data.removePrefix(FILE_URI_PREFIX)
        } else if (data.startsWith(ABSOLUTE_PATH_PREFIX)) {
            data
        } else {
            return null
        }
        return File(path).takeIf { it.exists() }
    }

    private fun readFileBytes(file: File): FetchResult? =
        try {
            FetchResult.Bytes(file.readBytes())
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val FILE_SCHEME = "file"
        const val FILE_URI_PREFIX = "file://"
        const val ABSOLUTE_PATH_PREFIX = "/"
    }
}
