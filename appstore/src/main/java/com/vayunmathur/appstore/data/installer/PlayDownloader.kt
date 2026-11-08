package com.vayunmathur.appstore.data.installer

import android.content.Context
import com.vayunmathur.library.log.Log
import com.aurora.gplayapi.data.models.PlayFile
import com.vayunmathur.appstore.data.DownloadCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads Play Store split APKs with resume support.
 *
 * There is deliberately no publisher-key check here and there cannot be one: Play App
 * Signing means Google holds the key, so nothing Play returns can be pinned to a
 * publisher. What integrity checking is possible happens in
 * [com.vayunmathur.appstore.data.security.InstallVerifier] just before install — package
 * identity, a single consistent signer across all splits, and continuity with the copy
 * already on the device.
 */
class PlayDownloader(
    private val context: Context
) {
    companion object {
        private const val TAG = "PlayDownloader"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val COPY_BUFFER_SIZE = 8192
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299
        private const val HTTP_PARTIAL = 206
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_GONE = 410

        /** True only when a Range resume is safe: partial body for a non-empty .tmp. */
        internal fun resumesPartial(responseCode: Int, existingBytes: Long): Boolean =
            existingBytes > 0 && responseCode == HTTP_PARTIAL
    }

    class ExpiredUrlException(message: String) : java.io.IOException(message)

    /**
     * Download list of PlayFiles to cache dir, verify, and return local Files.
     */
    suspend fun downloadFiles(
        packageName: String,
        versionCode: Long,
        gplayFiles: List<PlayFile>,
        progressCallback: (Float) -> Unit = {}
    ): Result<List<File>> {
        return try {
            val baseDir = DownloadCache.playDir(context.cacheDir, packageName, versionCode)
                .apply { mkdirs() }
            val totalSize = gplayFiles.sumOf { it.size }.takeIf { it > 0 } ?: -1L
            var totalDownloaded = 0L
            val localFiles = mutableListOf<File>()

            for ((index, gFile) in gplayFiles.withIndex()) {
                val fileName = gFile.name.takeIf { it.isNotBlank() } ?: "file_${index}.apk"
                val destFile = File(baseDir, fileName)
                val tmpFile = File(baseDir, "$fileName.tmp")

                if (isCachedComplete(destFile, gFile.size)) {
                    localFiles.add(destFile)
                    totalDownloaded += destFile.length()
                    continue
                }

                val result = downloadSingleFile(gFile, destFile, tmpFile) { bytesDownloaded ->
                    progressCallback(
                        overallProgress(index, gFile, bytesDownloaded, totalDownloaded, totalSize, gplayFiles.size)
                    )
                }

                if (result.isFailure) {
                    val cause = result.exceptionOrNull() ?: java.io.IOException("Download failed for $fileName")
                    return Result.failure(cause)
                }

                val file = result.getOrNull()!!
                localFiles.add(file)
                totalDownloaded += file.length()
            }

            Result.success(localFiles)
        } catch (expected: java.io.IOException) {
            Log.error(TAG, "downloadFiles failed: ${expected.message}", expected)
            Result.failure(expected)
        } catch (expected: SecurityException) {
            Log.error(TAG, "downloadFiles failed: ${expected.message}", expected)
            Result.failure(expected)
        }
    }

    private fun isCachedComplete(destFile: File, expectedSize: Long): Boolean {
        if (!destFile.exists() || destFile.length() <= 0) return false
        return expectedSize <= 0 || destFile.length() == expectedSize
    }

    private fun overallProgress(
        index: Int,
        gFile: PlayFile,
        bytesDownloaded: Long,
        totalDownloaded: Long,
        totalSize: Long,
        fileCount: Int,
    ): Float {
        val overall = if (totalSize > 0) {
            (totalDownloaded + bytesDownloaded).toFloat() / totalSize
        } else {
            val fileFraction = if (gFile.size > 0) bytesDownloaded.toFloat() / gFile.size else 0f
            (index.toFloat() + fileFraction) / fileCount
        }
        return overall.coerceIn(0f, 1f)
    }

    private fun downloadSingleFile(
        gFile: PlayFile,
        destFile: File,
        tmpFile: File,
        progressCallback: (Long) -> Unit
    ): Result<File> {
        val urlString = gFile.url.takeIf { it.isNotBlank() }
            ?: return Result.failure(java.io.IOException("Empty URL for ${gFile.name}"))
        val existing = if (tmpFile.exists()) tmpFile.length() else 0L

        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            useCaches = false
            if (existing > 0) {
                setRequestProperty("Range", "bytes=$existing-")
            }
        }

        val code = openConnection(conn) ?: return Result.failure(java.io.IOException("No response"))
        if (code !in HTTP_OK_MIN..HTTP_OK_MAX && code != HTTP_PARTIAL) {
            conn.disconnect()
            return expiredOrFailed(code)
        }

        val input = openInput(conn)
            ?: return Result.failure(java.io.IOException("Empty body"))
        // Resume only on a real partial response. A server (or CDN) that
        // ignores Range answers 200 with the whole body; appending that to
        // the partial .tmp corrupts the file, and the install then fails
        // verification minutes later instead of simply starting over.
        val append = resumesPartial(code, existing)
        return try {
            streamToTmp(input, tmpFile, if (append) existing else 0L, progressCallback)
            promoteTmp(tmpFile, destFile)
            Result.success(destFile)
        } catch (expected: java.io.IOException) {
            Result.failure(expected)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(conn: HttpURLConnection): Int? {
        return try {
            conn.responseCode
        } catch (expected: java.io.IOException) {
            conn.disconnect()
            null
        }
    }

    private fun openInput(conn: HttpURLConnection): java.io.InputStream? {
        return try {
            conn.inputStream
        } catch (_: java.io.IOException) {
            conn.disconnect()
            null
        }
    }

    private fun expiredOrFailed(code: Int): Result<File> {
        return if (code == HTTP_FORBIDDEN || code == HTTP_GONE) {
            Result.failure(ExpiredUrlException("URL expired $code"))
        } else {
            Result.failure(java.io.IOException("HTTP $code"))
        }
    }

    private fun streamToTmp(
        input: java.io.InputStream,
        tmpFile: File,
        existing: Long,
        progressCallback: (Long) -> Unit,
    ) {
        val output = java.io.FileOutputStream(tmpFile, existing > 0)
        var downloaded = existing
        output.use { out ->
            input.use { inp ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var read: Int
                while (inp.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    downloaded += read
                    progressCallback(downloaded)
                }
            }
        }
    }

    private fun promoteTmp(tmpFile: File, destFile: File) {
        if (tmpFile.exists()) {
            if (destFile.exists()) destFile.delete()
            tmpFile.renameTo(destFile)
        }
    }
}
