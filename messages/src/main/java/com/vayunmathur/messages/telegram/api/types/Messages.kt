package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

data class Message(
    val id: Int,
    val fromId: TlObject? = null,
    val peerId: TlObject,
    val date: Int,
    val message: String,
    val out: Boolean = false,
    val mediaTypeId: Int = 0,
    val replyMarkup: ByteArray? = null,
) : TlObject {
    override val typeId = 0x94345242.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): Message {
            val flags = Fields.decode(buf)
            val flags2 = Fields.decode(buf)
            val out = flags.has(1)
            val id = buf.int32()
            val fromId = if (flags.has(8)) decodePeer(buf) else null
            val peerId = decodePeer(buf)
            if (flags.has(28)) decodePeer(buf) // saved_peer_id
            if (flags.has(2)) {
                buf.int32() // fwd_from constructor id
                TlSkip.skipMessageFwdHeader(buf)
            }
            if (flags.has(11)) buf.int64() // via_bot_id
            if (flags.has(22)) buf.int64() // via_business_bot_id
            if (flags.has(3)) TlSkip.skipReplyTo(buf) // reply_to
            val date = buf.int32()
            val message = buf.string()
            val mediaTypeId = if (flags.has(9)) buf.peekId() else 0
            return Message(id, fromId, peerId, date, message, out, mediaTypeId)
        }
    }
}

object MessageEmpty : TlObject {
    override val typeId = 0x90a6ca84.toInt()
    override fun encode(buf: TlBuffer) {}
    fun decode(buf: TlBuffer): MessageEmpty {
        val flags = Fields.decode(buf)
        buf.int32() // id
        if (flags.has(0)) decodePeer(buf) // peer_id
        return MessageEmpty
    }
}

data class MessageService(
    val id: Int,
    val peerId: TlObject,
    val date: Int,
    val out: Boolean,
) : TlObject {
    override val typeId = 0x2b085862.toInt()
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer): MessageService {
            val flags = Fields.decode(buf)
            val out = flags.has(1)
            val id = buf.int32()
            val fromId = if (flags.has(8)) decodePeer(buf) else null
            val peerId = decodePeer(buf)
            if (flags.has(3)) { buf.int32(); buf.int32() }
            val date = buf.int32()
            return MessageService(id, peerId, date, out)
        }
    }
}
