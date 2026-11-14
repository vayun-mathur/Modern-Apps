package com.vayunmathur.communicate.telephony

import java.io.ByteArrayOutputStream

/**
 * Minimal MMS PDU composer for outgoing group/media messages (M-Send.req), following the
 * WAP-209 MMS Encapsulation + WSP encoding specs. Supports multiple recipients (group MMS),
 * a text/plain part, and image/video parts. The resulting bytes are handed to
 * [android.telephony.SmsManager.sendMultimediaMessage] via a content Uri.
 *
 * This is a compact, dependency-free encoder (AOSP's `com.google.android.mms.pdu` is @hide).
 * It is best-effort and carrier-dependent — see the plan's caveat; the caller persists the
 * message to the provider regardless so it shows in the thread.
 */
object MmsPdu {
    // Header field identifiers (already OR'd with 0x80 = "well-known field, short form").
    private const val FIELD_MESSAGE_TYPE = 0x8C
    private const val FIELD_TRANSACTION_ID = 0x98
    private const val FIELD_MMS_VERSION = 0x8D
    private const val FIELD_FROM = 0x89
    private const val FIELD_TO = 0x97
    private const val FIELD_DATE = 0x85
    private const val FIELD_SUBJECT = 0x96
    private const val FIELD_CONTENT_TYPE = 0x84

    private const val MESSAGE_TYPE_SEND_REQ = 0x80
    private const val MMS_VERSION_1_2 = 0x92 // short-integer for version 1.2
    private const val INSERT_ADDRESS_TOKEN = 0x81
    private const val FROM_ADDRESS_PRESENT = 0x80
    // WSP value markers.
    private const val FIELD_CONTENT_LOCATION = 0x8E
    private const val QUOTE_TOKEN = 0x7F
    private const val BYTE_MASK = 0xFF
    private const val END_STRING = 0x00
    private const val CHARSET_UTF8_SHORT = 0xEA
    private const val SHORT_LENGTH_MAX = 31
    private const val LENGTH_QUOTE = 0x1F
    private const val UINTVAR_BITS = 7
    private const val UINTVAR_MASK = 0x7F
    private const val UINTVAR_CONT = 0x80

    private const val CT_MULTIPART_MIXED = "application/vnd.wap.multipart.mixed"

    /** One part of a composed MMS: content type, bytes and optional name. */
    data class Part(val contentType: String, val data: ByteArray, val name: String? = null)

    /**
     * Compose an M-Send.req PDU.
     * @param recipients E.164 / phone numbers of every group member.
     * @param text optional text body (added as a text/plain part).
     * @param mediaParts image/video parts.
     */
    fun composeSendReq(
        transactionId: String,
        recipients: List<String>,
        text: String?,
        mediaParts: List<Part> = emptyList(),
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // --- Headers ---
        out.write(FIELD_MESSAGE_TYPE); out.write(MESSAGE_TYPE_SEND_REQ)
        out.write(FIELD_TRANSACTION_ID); writeTextString(out, transactionId)
        out.write(FIELD_MMS_VERSION); out.write(MMS_VERSION_1_2)
        // From: insert-address-token (the MMSC fills in our number).
        out.write(FIELD_FROM); out.write(1); out.write(INSERT_ADDRESS_TOKEN)
        // One To: header per recipient (PLMN-typed phone numbers).
        for (r in recipients) {
            out.write(FIELD_TO)
            writeEncodedString(out, "$r/TYPE=PLMN")
        }

        // --- Body: Content-Type = multipart.mixed, then the multipart entries ---
        out.write(FIELD_CONTENT_TYPE)
        writeTextString(out, CT_MULTIPART_MIXED)

        val parts = ArrayList<Part>()
        if (!text.isNullOrEmpty()) parts.add(Part("text/plain", text.toByteArray(Charsets.UTF_8)))
        parts.addAll(mediaParts)

        writeUintvar(out, parts.size.toLong())
        for ((i, p) in parts.withIndex()) {
            val headers = ByteArrayOutputStream()
            // Part content-type (part of HeadersLen).
            writeTextString(headers, p.contentType)
            // Content-Location / name so the receiver can reference the part.
            val name = p.name ?: defaultPartName(p.contentType, i)
            headers.write(FIELD_CONTENT_LOCATION) // Content-Location well-known field
            writeTextString(headers, name)
            val headerBytes = headers.toByteArray()
            writeUintvar(out, headerBytes.size.toLong())
            writeUintvar(out, p.data.size.toLong())
            out.write(headerBytes)
            out.write(p.data)
        }
        return out.toByteArray()
    }

    private fun defaultPartName(ct: String, index: Int): String = when {
        ct.startsWith("image/") -> "image_$index.${ct.substringAfter('/')}"
        ct.startsWith("video/") -> "video_$index.${ct.substringAfter('/')}"
        ct == "text/plain" -> "text_$index.txt"
        else -> "part_$index"
    }

    /** Text-string = [Quote] *TEXT End-of-string(0x00). Quote (0x7F) prefix if first byte >= 0x80. */
    private fun writeTextString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty() && (bytes[0].toInt() and BYTE_MASK) >= FROM_ADDRESS_PRESENT) out.write(QUOTE_TOKEN)
        out.write(bytes)
        out.write(END_STRING)
    }

    /**
     * Encoded-string-value = Text-string | Value-length Char-set Text-string. We use the UTF-8
     * form: Value-length, charset (UTF-8 = 106 = 0x6A, as short-integer 0xEA), then the text-string.
     */
    private fun writeEncodedString(out: ByteArrayOutputStream, value: String) {
        val text = ByteArrayOutputStream()
        text.write(CHARSET_UTF8_SHORT) // charset UTF-8 (106) as short-integer
        writeTextString(text, value)
        val body = text.toByteArray()
        writeValueLength(out, body.size.toLong())
        out.write(body)
    }

    /** Value-length = Short-length (0..30) | (0x1F Length-uintvar). */
    private fun writeValueLength(out: ByteArrayOutputStream, length: Long) {
        if (length < SHORT_LENGTH_MAX) {
            out.write(length.toInt())
        } else {
            out.write(LENGTH_QUOTE)
            writeUintvar(out, length)
        }
    }

    /** Variable-length unsigned integer (uintvar), 7 bits per byte, MSB = continuation. */
    private fun writeUintvar(out: ByteArrayOutputStream, value: Long) {
        if (value < FROM_ADDRESS_PRESENT) {
            out.write(value.toInt())
            return
        }
        val bytes = ArrayList<Int>()
        var v = value
        bytes.add((v and UINTVAR_MASK.toLong()).toInt())
        v = v shr UINTVAR_BITS
        while (v > 0) {
            bytes.add(((v and UINTVAR_MASK.toLong()) or UINTVAR_CONT.toLong()).toInt())
            v = v shr UINTVAR_BITS
        }
        for (i in bytes.indices.reversed()) out.write(bytes[i])
    }
}
