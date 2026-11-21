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
 * The latest unpublished outbound fix per recipient, held while the relay socket is down.
 *
 * Outbound counterpart of [InboundSpool]. When the socket is down, a publish used to fail
 * and the fix was gone: the heartbeat at least republishes on its next tick, but a parting
 * report (shutdown / low battery) is one-shot, and a still phone may not produce a newer
 * fix for a long time. This spool keeps the newest failed fix for each recipient so the
 * next (re)connect can deliver it before the next live publish.
 *
 * Mailbox-slot semantics, deliberately not a queue: each recipient holds exactly one fix,
 * the newest by `reportedAt`. A newer fix replaces the slot, an older one never displaces
 * it, and the file is bounded by the number of recipients rather than the length of the
 * outage. Staleness is the receiver's call (it ranks by `reportedAt`), so delivering the
 * latest-known position late is still truthful.
 *
 * Only sealed ciphertext is ever stored — the bytes [com.vayunmathur.findfamily.network.Networking]
 * would have put on the socket — in device-protected storage, mirroring [InboundSpool]:
 * holding the ciphertext here exposes nothing new (the keys live in the same storage),
 * nothing is decrypted or written in plaintext, and the pre-unlock publish path can spool
 * and flush too. A slot is cleared only when its exact payload is acked by a successful
 * socket send, or retired by a successful live publish of a newer-or-equal fix.
 *
 * Record format: `[u64 BE recipient][i64 BE reportedAtMs][u32 BE length][sealed payload]`.
 * Unlike [InboundSpool] the whole file is rewritten per mutation (temp file, fsync,
 * rename), because slots are replaced rather than appended; a torn or corrupt tail record
 * is ignored on read and every complete record before it is kept.
 */
object OutboundSpool {
    private const val FILE_NAME = "ff_outbound_spool.bin"

    /** Largest single sealed payload accepted; guards against reading a corrupt length. */
    private const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024

    /** recipient (u64) + reportedAtMs (i64) + length prefix (u32). */
    private const val RECORD_HEADER_BYTES = 20

    private val lock = Mutex()

    /** One spooled fix: the sealed [payload] for [recipient], ranked by [reportedAtMs]. */
    internal class Entry(val recipient: Long, val reportedAtMs: Long, val payload: ByteArray)

    private fun file(context: Context): File =
        File(context.createDeviceProtectedStorageContext().filesDir, FILE_NAME)

    /**
     * Make [payload] the spooled fix for [recipient], unless the slot already holds a
     * newer one (by [reportedAtMs]). Called when a publish fails because the socket is
     * down; the payload must already be sealed for this recipient.
     */
    suspend fun store(context: Context, recipient: Long, reportedAtMs: Long, payload: ByteArray) {
        if (payload.size > MAX_PAYLOAD_BYTES) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                val f = file(context)
                val slots = readAll(f)
                val existing = slots[recipient]
                if (existing != null && reportedAtMs < existing.reportedAtMs) return@withContext
                slots[recipient] = Entry(recipient, reportedAtMs, payload)
                writeAll(f, slots)
            }
        }
    }

    /** The latest spooled payload per recipient, as a snapshot. */
    suspend fun pending(context: Context): Map<Long, ByteArray> = lock.withLock {
        withContext(Dispatchers.IO) {
            readAll(file(context)).mapValues { it.value.payload }
        }
    }

    /**
     * Clear [recipient]'s slot after a successful send, but only if the slot still holds
     * exactly [payload]: a newer fix spooled while this send was in flight must survive
     * the older fix's acknowledgement.
     */
    suspend fun ack(context: Context, recipient: Long, payload: ByteArray) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                val f = file(context)
                val slots = readAll(f)
                val existing = slots[recipient] ?: return@withContext
                if (!existing.payload.contentEquals(payload)) return@withContext
                slots.remove(recipient)
                writeAll(f, slots)
            }
        }
    }

    /**
     * Retire [recipient]'s slot after a successful live publish, when the spooled fix is
     * no newer than the one just sent ([reportedAtMs]). A newer spooled fix is kept.
     */
    suspend fun clearSuperseded(context: Context, recipient: Long, reportedAtMs: Long) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                val f = file(context)
                val slots = readAll(f)
                val existing = slots[recipient] ?: return@withContext
                if (existing.reportedAtMs > reportedAtMs) return@withContext
                slots.remove(recipient)
                writeAll(f, slots)
            }
        }
    }

    /**
     * Send every spooled fix, latest per recipient, oldest slot first. Each slot is
     * cleared only when [send] reports success for its exact payload (see [ack]); a
     * failed send leaves the slot for the next reconnect. The lock is not held while
     * sending, so publishes can keep spooling during a flush. Returns how many fixes
     * were delivered.
     */
    suspend fun flush(
        context: Context,
        send: suspend (recipient: Long, payload: ByteArray) -> Boolean,
    ): Int {
        val snapshot = lock.withLock {
            withContext(Dispatchers.IO) { readAll(file(context)).values.toList() }
        }
        var sent = 0
        for (entry in snapshot) {
            val ok = runCatching { send(entry.recipient, entry.payload) }.getOrDefault(false)
            if (ok) {
                ack(context, entry.recipient, entry.payload)
                sent++
            }
        }
        return sent
    }

    /** All complete records in [f], keyed by recipient; a corrupt or torn tail is ignored. */
    private fun readAll(f: File): LinkedHashMap<Long, Entry> {
        val out = LinkedHashMap<Long, Entry>()
        if (!f.exists()) return out
        DataInputStream(f.inputStream().buffered()).use { input ->
            while (true) {
                val entry = readNextRecord(input) ?: break
                val existing = out[entry.recipient]
                if (existing == null || entry.reportedAtMs >= existing.reportedAtMs) {
                    out[entry.recipient] = entry
                }
            }
        }
        return out
    }

    /** One complete record, or null at EOF, on a corrupt header, or on a torn final record. */
    private fun readNextRecord(input: DataInputStream): Entry? {
        val recipient: Long
        val reportedAtMs: Long
        val len: Int
        try {
            recipient = input.readLong()
            reportedAtMs = input.readLong()
            len = input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (len < 0 || len > MAX_PAYLOAD_BYTES) return null
        val buf = ByteArray(len)
        try {
            input.readFully(buf)
        } catch (_: EOFException) {
            return null // torn final record from a crash mid-write
        }
        return Entry(recipient, reportedAtMs, buf)
    }

    /** Rewrites the spool with [slots] (or deletes it when empty), fsynced, via tmp + rename. */
    private fun writeAll(f: File, slots: Map<Long, Entry>) {
        if (slots.isEmpty()) {
            f.delete()
            return
        }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            val out = DataOutputStream(fos)
            for (entry in slots.values) {
                out.writeLong(entry.recipient)
                out.writeLong(entry.reportedAtMs)
                out.writeInt(entry.payload.size)
                out.write(entry.payload)
            }
            out.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            // Same-directory rename is atomic; this is only a fallback for filesystems
            // that refuse it, at the cost of the atomicity.
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    /** Header bytes preceding each payload on disk (for tests). */
    internal const val RECORD_OVERHEAD: Int = RECORD_HEADER_BYTES
}
