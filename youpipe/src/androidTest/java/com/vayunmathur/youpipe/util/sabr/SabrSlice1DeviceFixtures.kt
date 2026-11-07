package com.vayunmathur.youpipe.util.sabr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.sabrng.YoutubeSabrInfo

/** Device-side copies of the JVM slice fixtures (androidTest cannot see src/test). */
object SabrSlice1DeviceFixtures {
    fun sidx(timescale: Int = 1000, durationsMs: List<Int>): ByteArray {
        val size = 8 + 4 + 4 + 4 + 4 + 4 + 2 + 2 + durationsMs.size * 12
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(size); buffer.put("sidx".toByteArray(Charsets.US_ASCII))
        buffer.putInt(0); buffer.putInt(1); buffer.putInt(timescale)
        buffer.putInt(0); buffer.putInt(0)
        buffer.putShort(0); buffer.putShort(durationsMs.size.toShort())
        for (duration in durationsMs) {
            buffer.putInt(1024); buffer.putInt(duration * timescale / 1000); buffer.putInt(0)
        }
        return buffer.array()
    }

    fun format(itag: Int, mime: String): YoutubeSabrInfo.Format =
        YoutubeSabrInfo.Format.fromParsedFormat(
            parsedFormat = ItagItem.getItag(itag), lastModified = 0L, xtags = null, mimeType = mime,
            audioTrackId = null, audioTrackDisplayName = null, drc = false,
            initializationUrl = null, initRangeStart = 0L, initRangeEnd = 0L,
        )
}

/** UMP part builders (device copies of SabrSlice1UmpServerTest's helpers). */
object SabrSlice1DeviceUmp {
    private fun umpInt(value: Int): ByteArray = when {
        value < 128 -> byteArrayOf(value.toByte())
        else -> byteArrayOf((128 + value % 64).toByte(), (value / 64).toByte())
    }
    private fun umpPart(type: Int, payload: ByteArray): ByteArray = umpInt(type) + umpInt(payload.size) + payload

    fun segmentParts(headerId: Int, itag: Int, init: Boolean, seq: Int, media: ByteArray): ByteArray {
        val header = org.schabi.newpipe.extractor.services.youtube.sabrng.protocol.SabrProto.Writer().apply {
            writeInt32(1, headerId); writeInt32(3, itag); writeBool(8, init)
            writeInt32(9, seq); writeUInt64(14, media.size.toLong())
        }.toByteArray()
        return umpPart(20, header) + umpPart(21, byteArrayOf(headerId.toByte()) + media) + umpPart(22, byteArrayOf(headerId.toByte()))
    }

    fun configInfo(vararg formats: YoutubeSabrInfo.Format): YoutubeSabrInfo =
        YoutubeSabrInfo("vidS1", "cpn", "1.0", null, "https://127.0.0.1:9/sabr", "AAAA", formats.toList(), null)
}
