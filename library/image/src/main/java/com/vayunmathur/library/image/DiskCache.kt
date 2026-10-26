package com.vayunmathur.library.image

import com.vayunmathur.library.image.util.ensureExists
import com.vayunmathur.library.image.util.sha256
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Very small file LRU disk cache.
 * - Key is hashed via SHA-256 to form a filename.
 * - Raw bytes are stored (encoded image bytes, e.g. JPEG/PNG).
 * - Eviction: oldest lastModified first when over size limit.
 * - Fixed at 200 MB.
 *
 * Locking is striped per key rather than global: during a fast scroll, dozens of
 * unrelated thumbnails are read and written at once, and a single lock made them
 * queue behind each other (and behind whichever one was doing a full directory
 * scan). Only [trim] is genuinely global, and it takes its own lock.
 */
class DiskCache internal constructor(
    private val directory: File,
    private val maxSizeBytes: Long,
) {
    private val stripes = Array(STRIPE_COUNT) { ReentrantLock() }
    private val trimLock = ReentrantLock()

    private val sizeLock = Any()
    private var totalBytes = 0L
    private var sizeSeeded = false

    init {
        directory.ensureExists()
    }

    fun get(key: String): ByteArray? {
        val file = fileFor(key)
        val bytes = stripeFor(key).withLock {
            if (!file.exists()) return null
            try {
                file.readBytes()
            } catch (_: Exception) {
                null
            }
        } ?: return null

        // Best-effort LRU touch, outside the lock and rate-limited. This used to be
        // a filesystem *write* on every single read, performed while holding the
        // one lock every other read and write also needed.
        try {
            val now = System.currentTimeMillis()
            if (now - file.lastModified() > TOUCH_INTERVAL_MS) file.setLastModified(now)
        } catch (_: Exception) {
        }
        return bytes
    }

    fun put(key: String, bytes: ByteArray) {
        // Seeded before the write lands, so this file is counted by its delta and not
        // a second time by the seeding scan.
        ensureSeeded()

        val file = fileFor(key)
        val delta = stripeFor(key).withLock {
            try {
                directory.ensureExists()
                val previous = if (file.exists()) file.length() else 0L
                // atomic write via temp
                val tmp = File(directory, "${file.name}$TMP_MARKER${System.nanoTime()}")
                tmp.writeBytes(bytes)
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    // The old file was already deleted above, so account for it.
                    -previous
                } else {
                    bytes.size.toLong() - previous
                }
            } catch (_: Exception) {
                return
            }
        }

        // Total size is maintained incrementally, so a write no longer costs a
        // listFiles() + sumOf { length() } + sortedBy { lastModified() } over the
        // whole directory. That scan now happens only when actually trimming.
        if (addToTotal(delta) > maxSizeBytes) trim()
    }

    fun remove(key: String) {
        ensureSeeded()
        val file = fileFor(key)
        stripeFor(key).withLock {
            try {
                val len = if (file.exists()) file.length() else 0L
                if (file.delete()) addToTotal(-len)
            } catch (_: Exception) {}
        }
    }

    /**
     * Removes every entry.
     *
     * Takes all stripes (in a fixed order, and never while one is already held) so an
     * in-flight [put] cannot rename its temp file into place after the sweep and
     * survive a clear.
     */
    fun clear() {
        withAllStripes {
            trimLock.withLock {
                try {
                    directory.listFiles()?.forEach { it.delete() }
                    setTotal(0L)
                } catch (_: Exception) {}
            }
        }
    }

    private fun fileFor(key: String): File = File(directory, key.sha256())

    private fun stripeFor(key: String): ReentrantLock {
        val h = key.hashCode()
        val spread = h xor (h ushr 16)
        return stripes[(spread and Int.MAX_VALUE) % STRIPE_COUNT]
    }

    private fun <T> withAllStripes(block: () -> T): T {
        stripes.forEach { it.lock() }
        try {
            return block()
        } finally {
            stripes.reversed().forEach { it.unlock() }
        }
    }

    /** Measures the directory once, so [addToTotal] only ever applies deltas. */
    private fun ensureSeeded() = synchronized(sizeLock) {
        if (!sizeSeeded) {
            totalBytes = measureDirectory()
            sizeSeeded = true
        }
    }

    private fun addToTotal(delta: Long): Long = synchronized(sizeLock) {
        totalBytes = (totalBytes + delta).coerceAtLeast(0L)
        totalBytes
    }

    private fun setTotal(value: Long) = synchronized(sizeLock) {
        totalBytes = value.coerceAtLeast(0L)
        sizeSeeded = true
    }

    private fun measureDirectory(): Long = try {
        directory.listFiles()?.filter { it.isFile && !it.name.contains(TMP_MARKER) }
            ?.sumOf { it.length() } ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun trim() {
        // Another thread is already trimming; its pass covers this overflow too.
        if (!trimLock.tryLock()) return
        try {
            // Note the TMP_MARKER exclusion: half-written files are not part of the
            // cache and must not be counted or deleted from under a writer.
            val files = directory.listFiles()?.filter { it.isFile && !it.name.contains(TMP_MARKER) }
                ?: return
            var remaining = files.sumOf { it.length() }
            if (remaining <= maxSizeBytes) return
            // oldest first
            var freed = 0L
            for (f in files.sortedBy { it.lastModified() }) {
                if (remaining - freed <= maxSizeBytes) break
                val len = f.length()
                if (f.delete()) freed += len
            }
            // Applied as a delta, not as an absolute from the (already stale) scan
            // above: writes that completed while this pass ran must keep their deltas.
            addToTotal(-freed)
        } catch (_: Exception) {
        } finally {
            trimLock.unlock()
        }
    }

    private companion object {
        const val STRIPE_COUNT = 16
        const val MAX_BYTES = 200L * 1024 * 1024

        /** Marks in-progress writes so they are never counted or evicted. */
        const val TMP_MARKER = ".tmp_"

        /**
         * Smallest gap between LRU touches of the same file. Eviction order only
         * needs to be roughly right, and a hot key would otherwise pay a
         * filesystem write on every read.
         */
        const val TOUCH_INTERVAL_MS = 60_000L
    }

    class Builder {
        private var directory: File? = null

        fun directory(dir: File): Builder {
            directory = dir
            return this
        }

        fun build(): DiskCache {
            val dir = directory ?: throw IllegalStateException("DiskCache directory not set")
            return DiskCache(dir, MAX_BYTES)
        }
    }
}
