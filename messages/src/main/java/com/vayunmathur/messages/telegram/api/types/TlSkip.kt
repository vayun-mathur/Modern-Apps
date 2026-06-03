package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer

object TlSkip {

    fun skipMessageFwdHeader(buf: TlBuffer) {
        val flags = Fields.decode(buf)
        if (flags.has(0)) decodePeer(buf) // from_id
        if (flags.has(5)) buf.string() // from_name
        buf.int32() // date
        if (flags.has(2)) buf.int32() // channel_post
        if (flags.has(3)) buf.string() // post_author
        if (flags.has(4)) { decodePeer(buf); buf.int32() } // saved_from_peer + saved_from_msg_id
        if (flags.has(8)) decodePeer(buf) // saved_from_id
        if (flags.has(9)) buf.string() // saved_from_name
        if (flags.has(10)) buf.int32() // saved_date
        if (flags.has(6)) buf.string() // psa_type
    }

    fun skipReplyTo(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x9c98bfc1.toInt() -> { // messageReplyStoryHeader
                decodePeer(buf) // peer
                buf.int32() // story_id
            }
            0xafbc09db.toInt() -> { // messageReplyHeader
                val flags = Fields.decode(buf)
                if (flags.has(4)) buf.int32() // reply_to_msg_id
                if (flags.has(0)) decodePeer(buf) // reply_to_peer_id
                if (flags.has(5)) {
                    buf.int32() // MessageFwdHeader type id
                    skipMessageFwdHeader(buf)
                }
                if (flags.has(8)) skipBoxedType(buf) // reply_media
                if (flags.has(1)) buf.int32() // reply_to_top_id
                if (flags.has(6)) buf.string() // quote_text
                if (flags.has(7)) skipVectorBoxed(buf) // quote_entities
                if (flags.has(10)) buf.int32() // quote_offset
            }
        }
    }

    fun skipPeerNotifySettings(buf: TlBuffer) {
        val typeId = buf.int32()
        if (typeId == 0x99622c0c.toInt()) {
            val flags = Fields.decode(buf)
            if (flags.has(0)) buf.int32() // show_previews (Bool type id)
            if (flags.has(1)) buf.int32() // mute_until
            if (flags.has(2)) skipNotificationSound(buf) // ios_sound
            if (flags.has(3)) skipNotificationSound(buf) // android_sound
            if (flags.has(4)) skipNotificationSound(buf) // other_sound
            if (flags.has(6)) buf.int32() // stories_muted (Bool type id)
            if (flags.has(7)) buf.int32() // stories_hide_sender (Bool type id)
            if (flags.has(8)) skipNotificationSound(buf) // stories_ios_sound
            if (flags.has(9)) skipNotificationSound(buf) // stories_android_sound
            if (flags.has(10)) skipNotificationSound(buf) // stories_other_sound
        }
    }

    private fun skipNotificationSound(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x97e8bebe.toInt() -> {} // notificationSoundDefault
            0x6f0c34df.toInt() -> {} // notificationSoundNone
            0x830b9ae4.toInt() -> { buf.string(); buf.string() } // notificationSoundLocal
            0xff6c8049.toInt() -> buf.int64() // notificationSoundRingtone
        }
    }

    fun skipChatPhoto(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x37c1011c.toInt() -> {} // chatPhotoEmpty
            0x1c6e1c11.toInt() -> { // chatPhoto
                val flags = Fields.decode(buf)
                buf.int64() // photo_id
                if (flags.has(1)) buf.bytes() // stripped_thumb
                buf.int32() // dc_id
            }
        }
    }

    fun skipUserProfilePhoto(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x4f11bae1.toInt() -> {} // userProfilePhotoEmpty
            0x82d1f706.toInt() -> { // userProfilePhoto
                val flags = Fields.decode(buf)
                buf.int64() // photo_id
                if (flags.has(1)) buf.bytes() // stripped_thumb
                buf.int32() // dc_id
            }
        }
    }

    fun skipUserStatus(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x09d05049.toInt() -> {} // userStatusEmpty
            0xedb93949.toInt() -> buf.int32() // userStatusOnline: expires
            0x008c703f.toInt() -> buf.int32() // userStatusOffline: was_online
            0xe26f42f1.toInt() -> {} // userStatusRecently
            0x07bf09fc.toInt() -> {} // userStatusLastWeek
            0x77ebc742.toInt() -> {} // userStatusLastMonth
        }
    }

    fun skipBoxedType(buf: TlBuffer) {
        val typeId = buf.int32()
        skipByTypeId(typeId, buf)
    }

    private fun skipByTypeId(typeId: Int, buf: TlBuffer) {
        when (typeId) {
            0x3ded6320.toInt() -> {} // messageMediaEmpty
            0x695150d7.toInt() -> { // messageMediaPhoto
                val flags = Fields.decode(buf)
                if (flags.has(0)) skipBoxedType(buf) // photo
                if (flags.has(2)) buf.int32() // ttl_seconds
            }
            0x56e0d474.toInt() -> { // messageMediaGeo
                skipBoxedType(buf) // geo
            }
            0x70322949.toInt() -> { // messageMediaContact
                buf.string(); buf.string(); buf.string(); buf.string(); buf.int64()
            }
            else -> {
                // For unknown types, consume remaining buffer cautiously
            }
        }
    }

    fun skipVectorBoxed(buf: TlBuffer) {
        val vecId = buf.int32()
        val count = buf.int32()
        for (i in 0 until count) {
            skipBoxedType(buf)
        }
    }
}
