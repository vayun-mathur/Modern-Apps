package com.vayunmathur.findfamily.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Relay frames received before the first unlock, held until they can be processed.
 *
 * The relay hands queued messages to whichever socket subscribes as us, and a plain
 * subscription does not acknowledge them: once a frame is sent, the server's copy is gone.
 * Before the first unlock the Room database (credential-encrypted storage) cannot be opened,
 * so the pre-unlock socket cannot store a peer's location. It used to drop every frame it
 * drained, losing whatever peers had queued while this phone was off.
 *
 * This spool keeps those frames, still encrypted exactly as the relay delivered them, in a
 * length-prefixed file in device-protected storage, which is readable before unlock. The
 * unlocked path replays them through the normal inbound pipeline (decrypt, pair-ID check,
 * store) and then deletes the file. Nothing is decrypted or written in plaintext before
 * unlock. The private key that can open these frames is already in device-protected storage
 * (see [DirectBootStore]), so holding the ciphertext here exposes nothing new.
 *
 * Record format: `[u32 BE length][frame bytes]`, appended and fsynced per frame so a crash or
 * power loss keeps every frame already written. A torn final record (length written, bytes
 * short) is ignored on replay; every complete record before it is kept.
 */
object InboundSpool {
    private const val FILE_NAME = "ff_inbound_spool.bin"

    /**
     * Above this the pre-unlock path stops draining the relay (it closes its socket), so the
     * rest stays queued on the server for the unlocked socket instead of being written here.
     * 64 MiB is ~45k location frames: far more than a phone receives between a reboot and its
     * passcode in practice.
     */
    const val SOFT_LIMIT_BYTES: Long = 64L * 1024 * 1024

    private const val LENGTH_PREFIX_BYTES = 4

    /** Largest single frame accepted on replay; guards against reading a corrupt length. */
    private const val MAX_FRAME_BYTES = 16 * 1024 * 1024

    private val lock = Mutex()

    private fun file(context: Context): File =
        File(context.createDeviceProtectedStorageContext().filesDir, FILE_NAME)

    /**
     * Appends one frame. Returns the spool size afterwards, so the caller can stop draining
     * once it passes [SOFT_LIMIT_BYTES]. The frame is always written (never refused), so no
     * frame the server already handed over is lost to the limit.
     */
    suspend fun append(context: Context, frame: ByteArray): Long = lock.withLock {
        withContext(Dispatchers.IO) {
            val f = file(context)
            f.parentFile?.mkdirs()
            FileOutputStream(f, true).use { fos ->
                val out = DataOutputStream(fos)
                out.writeInt(frame.size)
                out.write(frame)
                out.flush()
                fos.fd.sync()
            }
            f.length()
        }
    }

    /**
     * Hands every spooled frame, oldest first, to [process], then deletes the spool.
     *
     * Progress is checkpointed after each frame (the byte offset of the next record, fsynced
     * to a small sidecar file), so if the process dies mid-replay the next drain resumes where
     * it stopped: no frame is lost, and at most the one frame in flight is processed twice.
     * If [process] throws, the drain stops at that frame and leaves it and everything after it
     * spooled for the next attempt.
     *
     * Returns the number of frames processed by this call.
     */
    suspend fun drain(context: Context, process: suspend (ByteArray) -> Unit): Int = lock.withLock {
        val f = file(context)
        val progress = progressFile(context)
        if (!withContext(Dispatchers.IO) { f.exists() }) {
            withContext(Dispatchers.IO) { progress.delete() }
            return@withLock 0
        }
        var offset = withContext(Dispatchers.IO) { readOffset(progress) }
        val records = withContext(Dispatchers.IO) { readFrom(f, offset) }
        var processed = 0
        for ((frame, next) in records) {
            process(frame)
            offset = next
            withContext(Dispatchers.IO) { writeOffset(progress, offset) }
            processed++
        }
        withContext(Dispatchers.IO) {
            f.delete()
            progress.delete()
        }
        processed
    }

    private fun progressFile(context: Context): File =
        File(context.createDeviceProtectedStorageContext().filesDir, "$FILE_NAME.done")

    private fun readOffset(progress: File): Long =
        runCatching { progress.readText().trim().toLong() }.getOrDefault(0L).coerceAtLeast(0L)

    private fun writeOffset(progress: File, offset: Long) {
        FileOutputStream(progress, false).use { fos ->
            fos.write(offset.toString().toByteArray())
            fos.flush()
            fos.fd.sync()
        }
    }

    /** Complete records starting at byte [start], each with the offset of the record after it. */
    private fun readFrom(f: File, start: Long): List<Pair<ByteArray, Long>> {
        val out = ArrayList<Pair<ByteArray, Long>>()
        DataInputStream(f.inputStream().buffered()).use { input ->
            var pos = 0L
            while (pos < start) {
                val skipped = input.skip(start - pos)
                if (skipped <= 0) return out
                pos += skipped
            }
            while (true) {
                val len = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                if (len < 0 || len > MAX_FRAME_BYTES) break
                val buf = ByteArray(len)
                try {
                    input.readFully(buf)
                } catch (_: EOFException) {
                    break // torn final record from a crash mid-append
                }
                pos += LENGTH_PREFIX_BYTES + len
                out.add(buf to pos)
            }
        }
        return out
    }

    /** Bytes taken by the length prefix of each record (for tests). */
    internal const val RECORD_OVERHEAD: Int = LENGTH_PREFIX_BYTES
}
