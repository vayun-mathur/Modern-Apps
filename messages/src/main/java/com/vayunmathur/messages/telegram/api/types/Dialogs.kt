package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

data class Dialog(
    val peer: TlObject,
    val topMessage: Int,
    val readInboxMaxId: Int,
    val readOutboxMaxId: Int,
    val unreadCount: Int,
) : TlObject {
    override val typeId = 0xd58a08c6.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): Dialog {
            val flags = Fields.decode(buf)
            val peer = decodePeer(buf)
            val topMessage = buf.int32()
            val readInboxMaxId = buf.int32()
            val readOutboxMaxId = buf.int32()
            val unreadCount = buf.int32()
            buf.int32() // unread_mentions_count
            buf.int32() // unread_reactions_count
            TlSkip.skipPeerNotifySettings(buf) // notifySettings (mandatory)
            if (flags.has(0)) buf.int32() // pts
            if (flags.has(1)) TlSkip.skipBoxedType(buf) // draft
            if (flags.has(4)) buf.int32() // folder_id
            if (flags.has(5)) buf.int32() // ttl_period
            return Dialog(peer, topMessage, readInboxMaxId, readOutboxMaxId, unreadCount)
        }
    }
}
