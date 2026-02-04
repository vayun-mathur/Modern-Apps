package com.vayunmathur.maps // Update this to your actual package

import android.content.Context
import android.util.Log
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.File

object OSM {
    private const val TAG = "OSM_Provider"

    // Segmented buffers to bypass the 2GB MappedByteBuffer limit
    private var idBuffer: MappedByteBuffer? = null
    private var keyOffsetBuffer: MappedByteBuffer? = null
    private var valOffsetBuffer: MappedByteBuffer? = null
    private var keysBlobBuffer: MappedByteBuffer? = null
    private var valsBlobBuffer: MappedByteBuffer? = null

    private var tripletCount: Long = 0

    /**
     * Maps the 2.5GB+ binary file into memory-mapped segments.
     * Call this after the download is complete.
     */
    fun initialize(context: Context) {
        try {
            val file = File(context.getExternalFilesDir(null), "amenities_indexed.bin")
            val raf = RandomAccessFile(file, "r")
            val channel = raf.channel
            val totalSize = raf.length()

            // 1. Read Header (8-byte Triplet Count)
            val headerBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(headerBuf, 0)
            headerBuf.flip()
            tripletCount = headerBuf.long
            val n = tripletCount.toInt()

            // 2. Calculate Block Start Offsets
            val idStart = 8L
            val idSize = n * 8L

            val kOffStart = idStart + idSize
            val kOffSize = (n + 1) * 4L

            val vOffStart = kOffStart + kOffSize
            val vOffSize = (n + 1) * 4L

            val keysStart = vOffStart + vOffSize

            // Peek at the last key offset to determine keys blob size
            val lastKeyOffBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(lastKeyOffBuf, vOffStart - 4)
            lastKeyOffBuf.flip()
            val keysSize = lastKeyOffBuf.int.toLong()

            val valsStart = keysStart + keysSize
            val valsSize = totalSize - valsStart

            // 3. Map Segments (Individual columns < 2GB)
            idBuffer = channel.map(FileChannel.MapMode.READ_ONLY, idStart, idSize).apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }
            keyOffsetBuffer = channel.map(FileChannel.MapMode.READ_ONLY, kOffStart, kOffSize).apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }
            valOffsetBuffer = channel.map(FileChannel.MapMode.READ_ONLY, vOffStart, vOffSize).apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }
            keysBlobBuffer = channel.map(FileChannel.MapMode.READ_ONLY, keysStart, keysSize)
            valsBlobBuffer = channel.map(FileChannel.MapMode.READ_ONLY, valsStart, valsSize)

            Log.d(TAG, "Successfully mapped $tripletCount triplets")
            raf.close() // The mapping persists even after closing the channel/file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OSM data", e)
        }
    }

    /**
     * Retrieves all tags for a given Node ID using O(log n) search + O(1) jump.
     */
    suspend fun getTags(id: Long): Map<String, String> {
        val ids = idBuffer ?: return emptyMap()
        val n = tripletCount.toInt()

        // 1. Binary Search for any index matching this ID
        val matchIndex = findIndexById(id, n, ids)
        if (matchIndex == -1) return emptyMap()

        // 2. Sweep Left and Right to find the range of duplicate IDs
        var first = matchIndex
        while (first > 0 && ids.getLong((first - 1) * 8) == id) {
            first--
        }
        var last = matchIndex
        while (last < n - 1 && ids.getLong((last + 1) * 8) == id) {
            last++
        }

        // 3. Extract all Tag Triplets in the range [first, last]
        val resultMap = mutableMapOf<String, String>()

        // Use duplicated buffers for thread-safe concurrent access
        val kBlob = keysBlobBuffer?.duplicate() ?: return emptyMap()
        val vBlob = valsBlobBuffer?.duplicate() ?: return emptyMap()
        val kOffsets = keyOffsetBuffer ?: return emptyMap()
        val vOffsets = valOffsetBuffer ?: return emptyMap()

        for (i in first..last) {
            // Get Key
            val kStart = kOffsets.getInt(i * 4)
            val kEnd = kOffsets.getInt((i + 1) * 4)
            val keyBytes = ByteArray(kEnd - kStart)
            kBlob.position(kStart)
            kBlob.get(keyBytes)

            // Get Value
            val vStart = vOffsets.getInt(i * 4)
            val vEnd = vOffsets.getInt((i + 1) * 4)
            val valBytes = ByteArray(vEnd - vStart)
            vBlob.position(vStart)
            vBlob.get(valBytes)

            resultMap[String(keyBytes, Charsets.UTF_8)] = String(valBytes, Charsets.UTF_8)
        }

        return resultMap
    }

    private fun findIndexById(targetId: Long, n: Int, buf: MappedByteBuffer): Int {
        var low = 0
        var high = n - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midId = buf.getLong(mid * 8) // idBuffer starts at first ID, no header offset needed here
            when {
                midId < targetId -> low = mid + 1
                midId > targetId -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }
}