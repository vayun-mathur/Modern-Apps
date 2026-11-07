package com.vayunmathur.backup

import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Robolectric/android-all leave BackupDataInput/Output's native writer/reader
 * as no-ops, so KV entities written to a pipe simply vanish. These shadows
 * implement the same public stream contract (header, then data bytes) in Java
 * over the file descriptor, which is all the transport code under test uses.
 */
@Implements(BackupDataOutput::class)
class ShadowBackupDataOutput {
    private var out: DataOutputStream? = null

    @Implementation
    fun __constructor__(fd: FileDescriptor) {
        out = DataOutputStream(FileOutputStream(fd))
    }

    @Suppress("UNUSED_PARAMETER")
    @Implementation
    fun __constructor__(fd: FileDescriptor, quota: Long) {
        __constructor__(fd)
    }

    @Suppress("UNUSED_PARAMETER")
    @Implementation
    fun __constructor__(fd: FileDescriptor, quota: Long, flags: Int) {
        __constructor__(fd)
    }

    @Implementation
    fun writeEntityHeader(key: String, dataSize: Int): Int {
        val kb = key.toByteArray(Charsets.UTF_8)
        val o = out!!
        o.writeInt(kb.size); o.writeInt(dataSize); o.write(kb); o.flush()
        return kb.size
    }

    @Implementation
    fun writeEntityData(data: ByteArray, size: Int): Int {
        out!!.write(data, 0, size); out!!.flush(); return size
    }

    @Suppress("UNUSED_PARAMETER")
    @Implementation
    fun setKeyPrefix(prefix: String) = Unit
}

@Implements(BackupDataInput::class)
class ShadowBackupDataInput {
    private var input: DataInputStream? = null
    private var key: String = ""
    private var dataSize: Int = 0
    private var remaining: Int = 0

    @Implementation
    fun __constructor__(fd: FileDescriptor) {
        input = DataInputStream(FileInputStream(fd))
    }

    @Implementation
    fun readNextHeader(): Boolean {
        skipRemaining()
        val keyLen = try { input!!.readInt() } catch (ignored: EOFException) { return false }
        dataSize = input!!.readInt()
        val kb = ByteArray(keyLen); input!!.readFully(kb)
        key = String(kb, Charsets.UTF_8)
        remaining = if (dataSize > 0) dataSize else 0
        return true
    }

    @Implementation
    fun getKey(): String = key

    @Implementation
    fun getDataSize(): Int = dataSize

    @Implementation
    fun readEntityData(data: ByteArray, offset: Int, size: Int): Int {
        if (remaining <= 0) return 0
        val r = input!!.read(data, offset, minOf(size, remaining))
        if (r > 0) remaining -= r
        return r
    }

    @Implementation
    fun skipEntityData() = skipRemaining()

    private fun skipRemaining() {
        while (remaining > 0) {
            val skipped = input!!.skip(remaining.toLong())
            if (skipped > 0) remaining -= skipped.toInt()
            else if (input!!.read() < 0) remaining = 0 else remaining--
        }
    }
}
