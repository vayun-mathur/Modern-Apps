package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

data class Chat(
    val id: Long,
    val title: String,
    val participantsCount: Int,
) : TlObject {
    override val typeId = 0x41cbf256.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): Chat {
            val flags = Fields.decode(buf)
            val id = buf.int64()
            val title = buf.string()
            TlSkip.skipChatPhoto(buf) // photo (mandatory)
            val participantsCount = buf.int32()
            buf.int32() // date
            buf.int32() // version
            if (flags.has(6)) TlSkip.skipBoxedType(buf) // migrated_to
            if (flags.has(14)) TlSkip.skipBoxedType(buf) // admin_rights
            if (flags.has(18)) TlSkip.skipBoxedType(buf) // default_banned_rights
            return Chat(id, title, participantsCount)
        }
    }
}

data class Channel(
    val id: Long,
    val accessHash: Long,
    val title: String,
    val username: String,
    val megagroup: Boolean,
) : TlObject {
    override val typeId = 0x94f592db.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): Channel {
            val flags = Fields.decode(buf)
            val flags2 = Fields.decode(buf)
            val id = buf.int64()
            val accessHash = if (flags.has(13)) buf.int64() else 0L
            val title = buf.string()
            val username = if (flags.has(6)) buf.string() else ""
            TlSkip.skipChatPhoto(buf) // photo (mandatory)
            buf.int32() // date (mandatory)
            if (flags.has(9)) { // restriction_reason vector
                TlSkip.skipVectorBoxed(buf)
            }
            if (flags.has(14)) TlSkip.skipBoxedType(buf) // admin_rights
            if (flags.has(15)) TlSkip.skipBoxedType(buf) // banned_rights
            if (flags.has(18)) TlSkip.skipBoxedType(buf) // default_banned_rights
            if (flags.has(17)) buf.int32() // participants_count
            return Channel(id, accessHash, title, username, megagroup = flags.has(8))
        }
    }
}

data class ChatForbidden(val id: Long, val title: String) : TlObject {
    override val typeId = 0x6592a1a7.toInt()
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer) = ChatForbidden(buf.int64(), buf.string())
    }
}

data class ChannelForbidden(val id: Long, val accessHash: Long, val title: String) : TlObject {
    override val typeId = 0x17d493d5.toInt()
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer): ChannelForbidden {
            val flags = Fields.decode(buf)
            return ChannelForbidden(buf.int64(), buf.int64(), buf.string())
        }
    }
}
