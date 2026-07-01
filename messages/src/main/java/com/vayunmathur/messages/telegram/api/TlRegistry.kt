package com.vayunmathur.messages.telegram.api

import android.util.Log
import com.vayunmathur.messages.telegram.api.types.*
import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

object TlRegistry {
    private const val TAG = "TlRegistry"

    fun decode(buf: TlBuffer): TlObject {
        val typeId = buf.int32()
        return decodeById(typeId, buf)
    }

    fun decodeById(typeId: Int, buf: TlBuffer): TlObject {
        return when (typeId) {
            // Updates containers
            0x74ae4240.toInt() -> decodeUpdates(buf)
            0x725b04c3.toInt() -> decodeUpdatesCombined(buf)
            0x78d4dec1.toInt() -> decodeUpdateShort(buf)
            0x313bc7f8.toInt() -> decodeUpdateShortMessage(buf)
            0x4d6deea5.toInt() -> decodeUpdateShortChatMessage(buf)
            0xe317af7e.toInt() -> UpdatesTooLong

            // Individual updates
            0x1f2b0afd.toInt() -> decodeUpdateNewMessage(buf)
            0x62ba04d9.toInt() -> decodeUpdateNewChannelMessage(buf)
            0xa20db0e5.toInt() -> decodeUpdateDeleteMessages(buf)
            0xe40370a3.toInt() -> decodeUpdateEditMessage(buf)
            0x9e84bc99.toInt() -> decodeUpdateReadHistoryInbox(buf)
            0x1b3f4df7.toInt() -> decodeUpdateEditChannelMessage(buf)
            0xc32d5b12.toInt() -> decodeUpdateDeleteChannelMessages(buf)
            0x2f2f21bf.toInt() -> decodeUpdateReadHistoryOutbox(buf)
            0x635b4c09.toInt() -> UpdateChannel(buf.int64())
            0x922e6e10.toInt() -> decodeUpdateReadChannelInbox(buf)

            // Auth
            0x5e002502.toInt() -> AuthSentCode.decode(buf)
            0x2ea2c0d4.toInt() -> AuthAuthorization.decode(buf)
            0x957b50fb.toInt() -> AuthPassword.decode(buf)

            // QR login (auth.LoginToken)
            0x629f1980.toInt() -> AuthLoginTokenResult.decode(buf)
            0x068e9916 -> AuthLoginTokenMigrateTo.decode(buf)
            0x390d5c5e.toInt() -> AuthLoginTokenSuccess(decode(buf)) // inner auth.Authorization
            0x44747e9a.toInt() -> AuthAuthorizationSignUpRequired
            0x564fe691.toInt() -> UpdateLoginToken

            // Users
            0x31774388.toInt() -> User.decode(buf)
            0xd3bc4b7a.toInt() -> UserEmpty.decode(buf)

            // Chats
            0x41cbf256.toInt() -> Chat.decode(buf)
            0x1c32b11c.toInt() -> Channel.decode(buf)
            0x6592a1a7.toInt() -> ChatForbidden.decode(buf)
            0x17d493d5.toInt() -> ChannelForbidden.decode(buf)

            // Response containers
            0x15ba6c40.toInt() -> decodeMessagesDialogs(buf)
            0x71e094f3.toInt() -> decodeMessagesDialogsSlice(buf)
            0x1d73e7ea.toInt() -> decodeMessagesMessages(buf)
            0x5f206716.toInt() -> decodeMessagesMessagesSlice(buf)
            0xc776ba4e.toInt() -> decodeMessagesChannelMessages(buf)
            0xb3134d9d.toInt() -> decodeContactsFound(buf)

            // UpdatesState / UpdatesDifference
            0xa56c2a3e.toInt() -> UpdatesState.decode(buf)
            0x00f49ca0.toInt() -> decodeUpdatesDifference(buf)
            0xa8fb1981.toInt() -> decodeUpdatesDifferenceSlice(buf)
            0x5d75a138.toInt() -> UpdatesDifferenceEmpty(buf.int32(), buf.int32())
            0x4afe8f6d.toInt() -> UpdatesDifferenceTooLong(buf.int32())

            // Auth export/import
            0xb434e2b8.toInt() -> AuthExportedAuthorization.decode(buf)

            // Upload (file download)
            0x096a18d5.toInt() -> UploadFile.decode(buf)
            0xf18cda44.toInt() -> UploadFileCdnRedirect.decode(buf)

            // Channel difference
            0x2064674e.toInt() -> decodeChannelDifference(buf)
            0x3e11affb.toInt() -> decodeChannelDifferenceEmpty(buf)
            0xa4bcc6fe.toInt() -> decodeChannelDifferenceTooLong(buf)

            // Peers
            0x59511722.toInt() -> PeerUser(buf.int64())
            0x36c6019a.toInt() -> PeerChat(buf.int64())
            0xa2a5371e.toInt() -> PeerChannel(buf.int64())

            else -> {
                Log.w(TAG, "Unknown type: 0x${typeId.toUInt().toString(16)}")
                UnknownObject(typeId, buf.data())
            }
        }
    }

    fun decodeMessage(buf: TlBuffer): TlObject {
        val id = buf.int32()
        return when (id) {
            0x95ef6f2b.toInt() -> Message.decode(buf)
            0x90a6ca84.toInt() -> MessageEmpty.decode(buf)
            0x7a800e0a.toInt() -> MessageService.decode(buf)
            else -> UnknownObject(id, buf.data())
        }
    }

    fun decodeVector(buf: TlBuffer, elementDecoder: (TlBuffer) -> TlObject): List<TlObject> {
        val vecId = buf.int32() // TYPE_VECTOR
        val count = buf.int32()
        return (0 until count).map { elementDecoder(buf) }
    }

    private fun decodeUpdates(buf: TlBuffer): Updates {
        val updates = decodeVector(buf) { b -> val id = b.int32(); decodeById(id, b) }
        val users = decodeVector(buf) { b -> val id = b.int32(); decodeById(id, b) }
        val chats = decodeVector(buf) { b -> val id = b.int32(); decodeById(id, b) }
        val date = buf.int32()
        val seq = buf.int32()
        return Updates(updates, users, chats, date, seq)
    }

    private fun decodeUpdatesCombined(buf: TlBuffer): UpdatesCombined {
        val updates = decodeVector(buf) { b -> val id = b.int32(); decodeById(id, b) }
        val users = decodeVector(buf) { b -> val id = b.int32(); decodeById(id, b) }
        val chats = decodeVector(buf) { b -> val id = b.int32(); decodeById(id, b) }
        val date = buf.int32()
        val seqStart = buf.int32()
        val seq = buf.int32()
        return UpdatesCombined(updates, users, chats, date, seqStart, seq)
    }

    private fun decodeUpdateShort(buf: TlBuffer): UpdateShort {
        val update = decode(buf)
        val date = buf.int32()
        return UpdateShort(update, date)
    }

    private fun decodeUpdateShortMessage(buf: TlBuffer): UpdateShortMessage {
        val flags = Fields.decode(buf)
        val out = flags.has(1)
        val id = buf.int32()
        val userId = buf.int64()
        val message = buf.string()
        val pts = buf.int32()
        val ptsCount = buf.int32()
        val date = buf.int32()
        if (flags.has(2)) { buf.int32(); TlSkip.skipMessageFwdHeader(buf) } // fwd_from
        if (flags.has(11)) buf.int64() // via_bot_id
        if (flags.has(3)) TlSkip.skipReplyTo(buf) // reply_to
        if (flags.has(7)) TlSkip.skipVector(buf) { TlSkip.skipMessageEntity(it) } // entities
        if (flags.has(25)) buf.int32() // ttl_period
        return UpdateShortMessage(id, userId, message, pts, ptsCount, date, out)
    }

    private fun decodeUpdateShortChatMessage(buf: TlBuffer): UpdateShortChatMessage {
        val flags = Fields.decode(buf)
        val out = flags.has(1)
        val id = buf.int32()
        val fromId = buf.int64()
        val chatId = buf.int64()
        val message = buf.string()
        val pts = buf.int32()
        val ptsCount = buf.int32()
        val date = buf.int32()
        if (flags.has(2)) { buf.int32(); TlSkip.skipMessageFwdHeader(buf) } // fwd_from
        if (flags.has(11)) buf.int64() // via_bot_id
        if (flags.has(3)) TlSkip.skipReplyTo(buf) // reply_to
        if (flags.has(7)) TlSkip.skipVector(buf) { TlSkip.skipMessageEntity(it) } // entities
        if (flags.has(25)) buf.int32() // ttl_period
        return UpdateShortChatMessage(id, fromId, chatId, message, pts, ptsCount, date, out)
    }

    private fun decodeUpdateNewMessage(buf: TlBuffer): UpdateNewMessage {
        val msg = decodeMessage(buf)
        val pts = buf.int32()
        val ptsCount = buf.int32()
        return UpdateNewMessage(msg, pts, ptsCount)
    }

    private fun decodeUpdateNewChannelMessage(buf: TlBuffer): UpdateNewChannelMessage {
        val msg = decodeMessage(buf)
        val pts = buf.int32()
        val ptsCount = buf.int32()
        return UpdateNewChannelMessage(msg, pts, ptsCount)
    }

    private fun decodeUpdateDeleteMessages(buf: TlBuffer): UpdateDeleteMessages {
        val vecId = buf.int32()
        val count = buf.int32()
        val msgs = (0 until count).map { buf.int32() }
        val pts = buf.int32()
        val ptsCount = buf.int32()
        return UpdateDeleteMessages(msgs, pts, ptsCount)
    }

    private fun decodeUpdateEditMessage(buf: TlBuffer): UpdateEditMessage {
        val msg = decodeMessage(buf)
        val pts = buf.int32()
        val ptsCount = buf.int32()
        return UpdateEditMessage(msg, pts, ptsCount)
    }

    private fun decodeUpdateEditChannelMessage(buf: TlBuffer): UpdateEditChannelMessage {
        val msg = decodeMessage(buf)
        val pts = buf.int32()
        val ptsCount = buf.int32()
        return UpdateEditChannelMessage(msg, pts, ptsCount)
    }

    private fun decodeUpdateDeleteChannelMessages(buf: TlBuffer): UpdateDeleteChannelMessages {
        val channelId = buf.int64()
        val vecId = buf.int32()
        val count = buf.int32()
        val msgs = (0 until count).map { buf.int32() }
        val pts = buf.int32()
        val ptsCount = buf.int32()
        return UpdateDeleteChannelMessages(channelId, msgs, pts, ptsCount)
    }

    private fun decodeUpdateReadHistoryOutbox(buf: TlBuffer): UpdateReadHistoryOutbox {
        val peer = decodePeer(buf)
        val maxId = buf.int32()
        val pts = buf.int32()
        val ptsCount = buf.int32()
        return UpdateReadHistoryOutbox(peer, maxId, pts, ptsCount)
    }

    private fun decodeUpdateReadChannelInbox(buf: TlBuffer): UpdateReadChannelInbox {
        val flags = Fields.decode(buf)
        if (flags.has(0)) buf.int32() // folder_id
        val channelId = buf.int64()
        val maxId = buf.int32()
        buf.int32() // still_unread_count
        val pts = buf.int32()
        return UpdateReadChannelInbox(channelId, maxId, pts)
    }

    private fun decodeUpdateReadHistoryInbox(buf: TlBuffer): UpdateReadHistoryInbox {
        val flags = Fields.decode(buf)
        if (flags.has(0)) buf.int32() // folder_id
        val peer = decodePeer(buf)
        val maxId = buf.int32()
        buf.int32() // still_unread_count
        val pts = buf.int32()
        val ptsCount = buf.int32()
        return UpdateReadHistoryInbox(peer, maxId, pts, ptsCount)
    }

    private fun decodeDialog(buf: TlBuffer): TlObject {
        val id = buf.int32()
        return when (id) {
            0xfc89f7f3.toInt() -> Dialog.decode(buf)
            else -> UnknownObject(id, buf.data())
        }
    }

    fun decodeChat(buf: TlBuffer): TlObject {
        val id = buf.int32()
        return decodeById(id, buf)
    }

    fun decodeUser(buf: TlBuffer): TlObject {
        val id = buf.int32()
        return decodeById(id, buf)
    }

    private fun decodeMessagesDialogs(buf: TlBuffer): MessagesDialogs {
        val dialogs = decodeVector(buf) { decodeDialog(it) }.filterIsInstance<Dialog>()
        val messages = decodeVector(buf) { decodeMessage(it) }
        val chats = decodeVector(buf) { decodeChat(it) }
        val users = decodeVector(buf) { decodeUser(it) }
        return MessagesDialogs(dialogs, messages, chats, users)
    }

    private fun decodeMessagesDialogsSlice(buf: TlBuffer): MessagesDialogsSlice {
        val count = buf.int32()
        val dialogs = decodeVector(buf) { decodeDialog(it) }.filterIsInstance<Dialog>()
        val messages = decodeVector(buf) { decodeMessage(it) }
        val chats = decodeVector(buf) { decodeChat(it) }
        val users = decodeVector(buf) { decodeUser(it) }
        return MessagesDialogsSlice(count, dialogs, messages, chats, users)
    }

    private fun decodeMessagesMessages(buf: TlBuffer): MessagesMessages {
        val messages = decodeVector(buf) { decodeMessage(it) }
        skipTopicsVector(buf)
        val chats = decodeVector(buf) { decodeChat(it) }
        val users = decodeVector(buf) { decodeUser(it) }
        return MessagesMessages(messages, chats, users)
    }

    private fun decodeMessagesMessagesSlice(buf: TlBuffer): MessagesMessagesSlice {
        val flags = Fields.decode(buf)
        val count = buf.int32()
        if (flags.has(0)) buf.int32() // next_rate
        if (flags.has(2)) buf.int32() // offset_id_offset
        if (flags.has(3)) TlSkip.skipBoxedType(buf) // search_flood (SearchPostsFlood)
        val messages = decodeVector(buf) { decodeMessage(it) }
        skipTopicsVector(buf)
        val chats = decodeVector(buf) { decodeChat(it) }
        val users = decodeVector(buf) { decodeUser(it) }
        return MessagesMessagesSlice(count, messages, chats, users)
    }

    private fun decodeMessagesChannelMessages(buf: TlBuffer): MessagesChannelMessages {
        val flags = Fields.decode(buf)
        buf.int32() // pts
        val count = buf.int32()
        if (flags.has(2)) buf.int32() // offset_id_offset
        val messages = decodeVector(buf) { decodeMessage(it) }
        skipTopicsVector(buf)
        val chats = decodeVector(buf) { decodeChat(it) }
        val users = decodeVector(buf) { decodeUser(it) }
        return MessagesChannelMessages(count, messages, chats, users)
    }

    private fun skipTopicsVector(buf: TlBuffer) {
        TlSkip.skipVectorBoxed(buf)
    }

    private fun decodeContactsFound(buf: TlBuffer): ContactsFound {
        val myResults = decodeVector(buf) { decodePeer(it) }
        val results = decodeVector(buf) { decodePeer(it) }
        val chats = decodeVector(buf) { decodeChat(it) }
        val users = decodeVector(buf) { decodeUser(it) }
        return ContactsFound(myResults, results, chats, users)
    }

    private fun decodeUpdatesDifference(buf: TlBuffer): UpdatesDifference {
        val newMessages = decodeVector(buf) { decodeMessage(it) }
        val newEncryptedMessages = decodeVector(buf) { val id = it.int32(); decodeById(id, it) }
        val otherUpdates = decodeVector(buf) { val id = it.int32(); decodeById(id, it) }
        val chats = decodeVector(buf) { decodeChat(it) }
        val users = decodeVector(buf) { decodeUser(it) }
        val stateId = buf.int32() // UpdatesState constructor
        val state = UpdatesState.decode(buf)
        return UpdatesDifference(newMessages, newEncryptedMessages, otherUpdates, chats, users, state)
    }

    private fun decodeUpdatesDifferenceSlice(buf: TlBuffer): UpdatesDifferenceSlice {
        val newMessages = decodeVector(buf) { decodeMessage(it) }
        val newEncryptedMessages = decodeVector(buf) { val id = it.int32(); decodeById(id, it) }
        val otherUpdates = decodeVector(buf) { val id = it.int32(); decodeById(id, it) }
        val chats = decodeVector(buf) { decodeChat(it) }
        val users = decodeVector(buf) { decodeUser(it) }
        val stateId = buf.int32() // UpdatesState constructor
        val state = UpdatesState.decode(buf)
        return UpdatesDifferenceSlice(newMessages, newEncryptedMessages, otherUpdates, chats, users, state)
    }

    // updates.channelDifference#2064674e flags:# final:flags.0?true pts:int timeout:flags.1?int
    //   new_messages:Vector<Message> other_updates:Vector<Update> chats:Vector<Chat> users:Vector<User>
    private fun decodeChannelDifference(buf: TlBuffer): ChannelDifference {
        val flags = Fields.decode(buf)
        val final = flags.has(0)
        val pts = buf.int32()
        if (flags.has(1)) buf.int32() // timeout
        val newMessages = decodeVector(buf) { decodeMessage(it) }
        val otherUpdates = decodeVector(buf) { val id = it.int32(); decodeById(id, it) }
        val chats = decodeVector(buf) { decodeChat(it) }
        val users = decodeVector(buf) { decodeUser(it) }
        return ChannelDifference(final, pts, newMessages, otherUpdates, chats, users)
    }

    // updates.channelDifferenceEmpty#3e11affb flags:# final:flags.0?true pts:int timeout:flags.1?int
    private fun decodeChannelDifferenceEmpty(buf: TlBuffer): ChannelDifferenceEmpty {
        val flags = Fields.decode(buf)
        val final = flags.has(0)
        val pts = buf.int32()
        if (flags.has(1)) buf.int32() // timeout
        return ChannelDifferenceEmpty(final, pts)
    }

    // updates.channelDifferenceTooLong#a4bcc6fe flags:# final:flags.0?true timeout:flags.1?int
    //   dialog:Dialog messages:Vector<Message> chats:Vector<Chat> users:Vector<User>
    private fun decodeChannelDifferenceTooLong(buf: TlBuffer): ChannelDifferenceTooLong {
        val flags = Fields.decode(buf)
        val final = flags.has(0)
        if (flags.has(1)) buf.int32() // timeout
        val dialog = decodeDialog(buf)
        val pts = (dialog as? Dialog)?.pts ?: 0
        val messages = decodeVector(buf) { decodeMessage(it) }
        val chats = decodeVector(buf) { decodeChat(it) }
        val users = decodeVector(buf) { decodeUser(it) }
        return ChannelDifferenceTooLong(final, pts, messages, chats, users)
    }
}

data class UnknownObject(val actualTypeId: Int, val rawData: ByteArray) : TlObject {
    override val typeId = actualTypeId
    override fun encode(buf: TlBuffer) { buf.putRawBytes(rawData) }
}
