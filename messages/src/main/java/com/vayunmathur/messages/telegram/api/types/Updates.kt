package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// Update types for real-time events

data class UpdateNewMessage(val message: TlObject, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0x1f2b0afd.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateNewChannelMessage(val message: TlObject, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0x62ba04d9.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateDeleteMessages(val messages: List<Int>, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0xa20db0e5.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateEditMessage(val message: TlObject, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0xe40370a3.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateReadHistoryInbox(val peer: TlObject, val maxId: Int, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0x9e84bc99.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateReadChannelInbox(val channelId: Long, val maxId: Int, val pts: Int) : TlObject {
    override val typeId = 0x922e6e10.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateReadChannelOutbox(val channelId: Long, val maxId: Int) : TlObject {
    override val typeId = 0xb75f99a9.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateUserName(val userId: Long, val firstName: String, val lastName: String, val username: String) : TlObject {
    override val typeId = 0xa7848924.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateNotifySettings(val peer: TlObject, val muteUntil: Int) : TlObject {
    override val typeId = 0xbec268ef.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdatePinnedDialogs(val folderId: Int, val order: List<TlObject>) : TlObject {
    override val typeId = 0xfa0f3ca2.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateEditChannelMessage(val message: TlObject, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0x1b3f4df7.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateDeleteChannelMessages(val channelId: Long, val messages: List<Int>, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0xc32d5b12.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateReadHistoryOutbox(val peer: TlObject, val maxId: Int, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0x2f2f21bf.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateChannel(val channelId: Long) : TlObject {
    override val typeId = 0x635b4c09.toInt()
    override fun encode(buf: TlBuffer) {}
}

// updateLoginToken#564fe691 — server signals a QR token was scanned; re-export.
object UpdateLoginToken : TlObject {
    override val typeId = 0x564fe691.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateUserTyping(val userId: Long, val actionTypeId: Int) : TlObject {
    override val typeId = 0x2a17bf5c.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateChatUserTyping(val chatId: Long, val fromId: TlObject, val actionTypeId: Int) : TlObject {
    override val typeId = 0x83487af0.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateChannelUserTyping(val channelId: Long, val topMsgId: Int, val fromId: TlObject, val actionTypeId: Int) : TlObject {
    override val typeId = 0x8c88c923.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateChatTitle(val chatId: Long, val title: String) : TlObject {
    override val typeId = 0x4214f37f.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateMessageReactions(val peer: TlObject, val msgId: Int) : TlObject {
    override val typeId = 0x1e297bfa.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateBotMessageReaction(val peer: TlObject, val msgId: Int, val actorId: TlObject) : TlObject {
    override val typeId = 0xac21d3ce.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdatePhoneCall(val phoneCallTypeId: Int, val phoneCallId: Long) : TlObject {
    override val typeId = 0xab0f6b1e.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateChatDefaultBannedRights(val peer: TlObject, val defaultBannedRights: Int, val version: Int) : TlObject {
    override val typeId = 0x54c01850.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdatePeerBlocked(val peerId: TlObject, val blocked: Boolean) : TlObject {
    override val typeId = 0xebe07752.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateUserStatus(val userId: Long, val statusTypeId: Int) : TlObject {
    override val typeId = 0xe5bdf8de.toInt()
    override fun encode(buf: TlBuffer) {}
}

// Update container types
data class Updates(val updates: List<TlObject>, val users: List<TlObject>, val chats: List<TlObject>, val date: Int, val seq: Int) : TlObject {
    override val typeId = 0x74ae4240.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdatesCombined(val updates: List<TlObject>, val users: List<TlObject>, val chats: List<TlObject>, val date: Int, val seqStart: Int, val seq: Int) : TlObject {
    override val typeId = 0x725b04c3.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateShort(val update: TlObject, val date: Int) : TlObject {
    override val typeId = 0x78d4dec1.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateShortMessage(
    val id: Int, val userId: Long, val message: String, val pts: Int, val ptsCount: Int, val date: Int, val out: Boolean,
) : TlObject {
    override val typeId = 0x313bc7f8.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateShortChatMessage(
    val id: Int, val fromId: Long, val chatId: Long, val message: String, val pts: Int, val ptsCount: Int, val date: Int, val out: Boolean,
) : TlObject {
    override val typeId = 0x4d6deea5.toInt()
    override fun encode(buf: TlBuffer) {}
}

object UpdatesTooLong : TlObject {
    override val typeId = 0xe317af7e.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdatesState(val pts: Int, val qts: Int, val date: Int, val seq: Int, val unreadCount: Int) : TlObject {
    override val typeId = 0xa56c2a3e.toInt()
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer) = UpdatesState(buf.int32(), buf.int32(), buf.int32(), buf.int32(), buf.int32())
    }
}

data class UpdatesDifference(
    val newMessages: List<TlObject>,
    val newEncryptedMessages: List<TlObject>,
    val otherUpdates: List<TlObject>,
    val chats: List<TlObject>,
    val users: List<TlObject>,
    val state: UpdatesState,
) : TlObject {
    override val typeId = 0x00f49ca0.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdatesDifferenceSlice(
    val newMessages: List<TlObject>,
    val newEncryptedMessages: List<TlObject>,
    val otherUpdates: List<TlObject>,
    val chats: List<TlObject>,
    val users: List<TlObject>,
    val intermediateState: UpdatesState,
) : TlObject {
    override val typeId = 0xa8fb1981.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdatesDifferenceEmpty(val date: Int, val seq: Int) : TlObject {
    override val typeId = 0x5d75a138.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdatesDifferenceTooLong(val pts: Int) : TlObject {
    override val typeId = 0x4afe8f6d.toInt()
    override fun encode(buf: TlBuffer) {}
}

// ---- Channel difference (per-channel pts recovery) ----

data class ChannelDifference(
    val final: Boolean,
    val pts: Int,
    val newMessages: List<TlObject>,
    val otherUpdates: List<TlObject>,
    val chats: List<TlObject>,
    val users: List<TlObject>,
) : TlObject {
    override val typeId = 0x2064674e.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class ChannelDifferenceEmpty(
    val final: Boolean,
    val pts: Int,
) : TlObject {
    override val typeId = 0x3e11affb.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class ChannelDifferenceTooLong(
    val final: Boolean,
    val pts: Int,
    val messages: List<TlObject>,
    val chats: List<TlObject>,
    val users: List<TlObject>,
) : TlObject {
    override val typeId = 0xa4bcc6fe.toInt()
    override fun encode(buf: TlBuffer) {}
}
