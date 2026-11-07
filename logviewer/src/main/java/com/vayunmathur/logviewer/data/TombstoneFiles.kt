package com.vayunmathur.logviewer.data

import com.vayunmathur.library.log.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** A file together with the modification time it had when it was chosen. */
data class TimestampedFile(val file: File, val lastModified: Long)

/**
 * Finds the text tombstone that belongs to a native crash.
 *
 * There is no id linking an `ApplicationErrorReport` to a tombstone. What there is, is that
 * `NativeCrashListener` in system_server fills `crashInfo.stackTrace` from the same header
 * `tombstoned` later writes to disk - so the header is the key, and matching is a byte comparison
 * of the first N bytes of each candidate.
 *
 * `lastModified` is captured before the read and rechecked after it, twice. `/data/tombstones` is a
 * fixed-size ring that the platform recycles under its own naming, so the file behind a path can be
 * replaced between choosing it and reading it. A mismatch means the answer is a different crash's
 * tombstone, which is worse than no answer.
 */
internal object TombstoneFiles {

    private const val TAG = "TombstoneFiles"

    private const val DIRECTORY = "/data/tombstones"

    fun findByHeader(header: ByteArray, directory: File = File(DIRECTORY)): TimestampedFile? {
        val tombstones = directory.listFiles() ?: return null

        val candidates = tombstones.mapNotNull { file ->
            val name = file.name
            // `.pb` is the protobuf form of the same crash; only the text one can be shown as-is.
            if (name.endsWith(".pb") || !name.startsWith("tombstone_")) return@mapNotNull null
            val lastModified = file.lastModified()
            if (lastModified <= 0) return@mapNotNull null
            TimestampedFile(file, lastModified)
        }.sortedByDescending { it.lastModified }

        val buffer = ByteArray(header.size)
        for (candidate in candidates) {
            try {
                FileInputStream(candidate.file).use { stream ->
                    if (!stream.readFully(buffer)) return@use
                    if (!header.contentEquals(buffer)) return@use
                    if (candidate.file.lastModified() != candidate.lastModified) return null
                    return candidate
                }
            } catch (e: IOException) {
                Log.dev(TAG, "unable to read ${candidate.file}", e)
            }
        }
        return null
    }

    /**
     * `InputStream.readNBytes` would be the obvious call, but it only exists from API 33 and this
     * app supports 31. Returns false for a file shorter than the header, which cannot be a match.
     */
    private fun FileInputStream.readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }
}
