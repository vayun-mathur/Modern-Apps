package com.vayunmathur.translate.data

import com.vayunmathur.library.log.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * App-private copies of images other apps hand us through Share / Open with (#846).
 *
 * The sender's read grant on a shared content uri is temporary, but the image
 * route keeps its uri in navigation state and decodes it again whenever the screen
 * is rebuilt. So the bytes are copied here on arrival and the screen is given the
 * copy's file uri, which stays readable for as long as the file exists.
 *
 * Copies are bounded ([maxBytes]), must sniff as an image format the platform
 * decoder understands, and are pruned on every store: anything older than
 * [maxAgeMs] goes, and only the newest [maxFiles] survive. The copy just written is
 * never pruned by its own store.
 */
class SharedImageCache(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
    private val maxFiles: Int = MAX_FILES,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Outcome of [store]. */
    sealed interface Result {
        /** The image now lives at [file]. */
        data class Cached(val file: File) : Result

        /** The content does not start like any supported image format. */
        data object NotAnImage : Result

        /** The content is larger than the cache accepts. */
        data object TooLarge : Result

        /** The content could not be read or the copy could not be written. */
        data object Unreadable : Result
    }

    /**
     * Copies [input] into the cache. Does not close [input]. Blocking: call off the
     * main thread.
     */
    fun store(input: InputStream): Result = synchronized(LOCK) {
        try {
            storeLocked(input)
        } catch (e: IOException) {
            Log.status(TAG, "shared image copy failed", e)
            Result.Unreadable
        }
    }

    private fun storeLocked(input: InputStream): Result {
        val header = ByteArray(HEADER_BYTES)
        val headerLen = readFully(input, header)
        val extension = sniffImageExtension(header, headerLen) ?: return Result.NotAnImage
        if (!dir.isDirectory && !dir.mkdirs()) return Result.Unreadable

        val now = clock()
        var target = File(dir, "$PREFIX$now.$extension")
        var suffix = 1
        while (target.exists()) {
            target = File(dir, "$PREFIX$now-$suffix.$extension")
            suffix++
        }
        val part = File(dir, target.name + PART_SUFFIX)
        val written = try {
            part.outputStream().use { out ->
                out.write(header, 0, headerLen)
                copyBounded(input, out, headerLen.toLong())
            }
        } catch (e: IOException) {
            part.delete()
            throw e
        }
        if (written > maxBytes) {
            part.delete()
            return Result.TooLarge
        }
        if (!part.renameTo(target)) {
            part.delete()
            return Result.Unreadable
        }
        target.setLastModified(now)
        cleanUpLocked(keep = target)
        return Result.Cached(target)
    }

    /**
     * Copies until EOF or until one byte past [maxBytes] has been seen, whichever is
     * first, and returns the total count including [already] header bytes.
     */
    private fun copyBounded(input: InputStream, out: OutputStream, already: Long): Long {
        var total = already
        val buffer = ByteArray(COPY_BUFFER)
        while (total <= maxBytes) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return total
    }

    /** Prunes stale copies (see the class doc). [keep] is never deleted. */
    fun cleanUp(keep: File? = null) = synchronized(LOCK) { cleanUpLocked(keep) }

    private fun cleanUpLocked(keep: File?) {
        val now = clock()
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        // A leftover .part is a copy that died mid-write; nothing points at it.
        files.filter { it.name.endsWith(PART_SUFFIX) }.forEach { it.delete() }
        val copies = files
            .filter { it.name.startsWith(PREFIX) && !it.name.endsWith(PART_SUFFIX) }
            .sortedByDescending { it.lastModified() }
        copies.forEachIndexed { index, file ->
            if (file == keep) return@forEachIndexed
            val tooOld = now - file.lastModified() > maxAgeMs
            if (tooOld || index >= maxFiles) file.delete()
        }
    }

    /** Limits, naming and the format sniffer shared by every cache instance. */
    companion object {
        /** Subdirectory of the app cache dir holding the copies. */
        const val DIR_NAME = "shared_images"

        /** Largest image accepted (bytes). Big enough for any phone camera JPEG/HEIF. */
        const val MAX_BYTES: Long = 32L * 1024 * 1024

        /** Copies kept at most: enough for a few shares in a row plus back navigation. */
        const val MAX_FILES = 5

        /** Copies older than a day are dropped regardless of count. */
        const val MAX_AGE_MS: Long = 24L * 60 * 60 * 1000

        private const val TAG = "SharedImageCache"
        private const val PREFIX = "shared-"
        private const val PART_SUFFIX = ".part"
        private const val HEADER_BYTES = 16
        private const val COPY_BUFFER = 64 * 1024
        private val LOCK = Any()

        /** The cache under [cacheDir] (normally `Context.cacheDir`). */
        fun inCacheDir(cacheDir: File): SharedImageCache = SharedImageCache(File(cacheDir, DIR_NAME))

        /**
         * File extension for the image format [header] starts with, or null when it
         * is not one the platform decoder reads (JPEG, PNG, GIF, WebP, BMP, HEIF/AVIF).
         */
        @Suppress("MagicNumber", "CyclomaticComplexMethod", "ReturnCount")
        fun sniffImageExtension(header: ByteArray, length: Int = header.size): String? {
            fun at(i: Int) = if (i < length) header[i].toInt() and 0xFF else -1
            fun ascii(offset: Int, text: String) =
                text.indices.all { at(offset + it) == text[it].code }

            if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "jpg"
            if (at(0) == 0x89 && ascii(1, "PNG\r\n")) return "png"
            if (ascii(0, "GIF87a") || ascii(0, "GIF89a")) return "gif"
            if (ascii(0, "RIFF") && ascii(8, "WEBP")) return "webp"
            if (ascii(0, "BM") && length >= 6) return "bmp"
            if (ascii(4, "ftyp")) {
                val brand = (8 until 12).map { at(it) }
                if (brand.any { it < 0 }) return null
                val text = String(CharArray(4) { brand[it].toChar() })
                return when (text) {
                    "heic", "heix", "hevc", "heim", "heis", "mif1", "msf1" -> "heic"
                    "avif", "avis" -> "avif"
                    else -> null
                }
            }
            return null
        }

        private fun readFully(input: InputStream, into: ByteArray): Int {
            var len = 0
            while (len < into.size) {
                val n = input.read(into, len, into.size - len)
                if (n < 0) break
                len += n
            }
            return len
        }
    }
}
