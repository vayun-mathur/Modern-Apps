package com.vayunmathur.messages.telegram.api.types

import android.util.Log
import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer

object TlSkip {

    private const val TAG = "TlSkip"

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
            // ---- MessageMedia ----
            0x3ded6320.toInt() -> {} // messageMediaEmpty
            0x695150d7.toInt() -> { // messageMediaPhoto
                val flags = Fields.decode(buf)
                if (flags.has(0)) skipBoxedType(buf) // photo
                if (flags.has(2)) buf.int32() // ttl_seconds
            }
            0x4cf4d72d.toInt() -> { // messageMediaDocument
                val flags = Fields.decode(buf)
                if (flags.has(0)) skipBoxedType(buf) // document
                if (flags.has(5)) skipBoxedType(buf) // alt_document
                if (flags.has(9)) skipBoxedType(buf) // video_cover (Photo)
                if (flags.has(10)) buf.int32() // video_timestamp
                if (flags.has(2)) buf.int32() // ttl_seconds
            }
            0x56e0d474.toInt() -> skipBoxedType(buf) // messageMediaGeo: geo
            0x70322949.toInt() -> { // messageMediaContact
                buf.string(); buf.string(); buf.string(); buf.string(); buf.int64()
            }
            0x9f84f49e.toInt() -> {} // messageMediaUnsupported
            0x2ec0533f.toInt() -> { // messageMediaVenue
                skipBoxedType(buf) // geo
                buf.string(); buf.string(); buf.string(); buf.string(); buf.string()
            }
            0xfdb19008.toInt() -> skipBoxedType(buf) // messageMediaGame: game
            0x3f7ee58b.toInt() -> { buf.int32(); buf.string() } // messageMediaDice: value emoticon
            0xddf10c3b.toInt() -> { // messageMediaWebPage
                Fields.decode(buf) // flags
                skipWebPage(buf)
            }

            // ---- Photo ----
            0x2331b22d.toInt() -> buf.int64() // photoEmpty: id
            0xfb197a65.toInt() -> { // photo
                val flags = Fields.decode(buf)
                buf.int64() // id
                buf.int64() // access_hash
                buf.bytes() // file_reference
                buf.int32() // date
                skipVector(buf) { skipPhotoSize(it) } // sizes
                if (flags.has(1)) skipVector(buf) { skipVideoSize(it) } // video_sizes
                buf.int32() // dc_id
            }

            // ---- GeoPoint ----
            0x1117dd5f.toInt() -> {} // geoPointEmpty
            0xb2a2f663.toInt() -> { // geoPoint
                val f = Fields.decode(buf)
                buf.double(); buf.double(); buf.int64() // long lat access_hash
                if (f.has(0)) buf.int32() // accuracy_radius
            }

            // ---- Document ----
            0x36f8c871.toInt() -> buf.int64() // documentEmpty: id
            0x8fd4c4d8.toInt() -> { // document
                val flags = Fields.decode(buf)
                buf.int64() // id
                buf.int64() // access_hash
                buf.bytes() // file_reference
                buf.int32() // date
                buf.string() // mime_type
                buf.int64() // size
                if (flags.has(0)) skipVector(buf) { skipPhotoSize(it) } // thumbs
                if (flags.has(1)) skipVector(buf) { skipVideoSize(it) } // video_thumbs
                buf.int32() // dc_id
                skipVector(buf) { skipDocumentAttribute(it) } // attributes
            }

            // ---- Game ----
            0xbdf9653b.toInt() -> { // game
                val flags = Fields.decode(buf)
                buf.int64(); buf.int64() // id access_hash
                buf.string(); buf.string(); buf.string() // short_name title description
                skipBoxedType(buf) // photo
                if (flags.has(0)) skipBoxedType(buf) // document
            }

            else -> {
                Log.w(TAG, "skipByTypeId: unknown type 0x${typeId.toUInt().toString(16)}, buffer may be corrupted")
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

    fun skipVector(buf: TlBuffer, elementSkip: (TlBuffer) -> Unit) {
        buf.int32() // vector constructor id
        val count = buf.int32()
        repeat(count) { elementSkip(buf) }
    }

    // ---- PhotoSize ----

    private fun skipPhotoSize(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xe17e23c0.toInt() -> buf.string() // photoSizeEmpty: type
            0x75c78e60.toInt() -> { buf.string(); buf.int32(); buf.int32(); buf.int32() } // photoSize
            0x021e1ad6.toInt() -> { buf.string(); buf.int32(); buf.int32(); buf.bytes() } // photoCachedSize
            0xe0b0bc2e.toInt() -> { buf.string(); buf.bytes() } // photoStrippedSize
            0xfa3efb95.toInt() -> { // photoSizeProgressive
                buf.string(); buf.int32(); buf.int32()
                val c = buf.int32(); buf.int32() // vector header
                repeat(c) { buf.int32() }
            }
            0xd8214d41.toInt() -> { buf.string(); buf.bytes() } // photoPathSize
            else -> Log.w(TAG, "Unknown PhotoSize: 0x${typeId.toUInt().toString(16)}")
        }
    }

    private fun skipVideoSize(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xde33b094.toInt() -> { // videoSize
                val flags = Fields.decode(buf)
                buf.string(); buf.int32(); buf.int32(); buf.int32() // type w h size
                if (flags.has(0)) buf.double() // video_start_ts
            }
            0x0f85c68f.toInt() -> { // videoSizeEmojiMarkup
                buf.int64() // emoji_id
                skipVector(buf) { it.int32() } // background_colors
            }
            0x0da082fe.toInt() -> { // videoSizeStickerMarkup
                skipInputStickerSet(buf)
                buf.int64() // sticker_id
                skipVector(buf) { it.int32() } // background_colors
            }
            else -> Log.w(TAG, "Unknown VideoSize: 0x${typeId.toUInt().toString(16)}")
        }
    }

    // ---- DocumentAttribute ----

    private fun skipDocumentAttribute(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x6c37c15c.toInt() -> { buf.int32(); buf.int32() } // imageSize: w h
            0x11b58939.toInt() -> {} // animated
            0x6319d612.toInt() -> { // sticker
                val flags = Fields.decode(buf)
                buf.string() // alt
                skipInputStickerSet(buf)
                if (flags.has(0)) { // mask_coords
                    buf.int32() // constructor
                    buf.int32(); buf.double(); buf.double(); buf.double() // n x y zoom
                }
            }
            0x17399fad.toInt() -> { // video
                val flags = Fields.decode(buf)
                buf.double(); buf.int32(); buf.int32() // duration w h
                if (flags.has(2)) buf.int32() // preload_prefix_size
                if (flags.has(4)) buf.double() // video_start_ts
                if (flags.has(5)) buf.string() // video_codec
            }
            0x9852f9c6.toInt() -> { // audio
                val flags = Fields.decode(buf)
                buf.int32() // duration
                if (flags.has(0)) buf.string() // title
                if (flags.has(1)) buf.string() // performer
                if (flags.has(2)) buf.bytes() // waveform
            }
            0x15590068.toInt() -> buf.string() // filename
            0x9801d2f7.toInt() -> {} // hasStickers
            0xfd149899.toInt() -> { // customEmoji
                Fields.decode(buf)
                buf.string() // alt
                skipInputStickerSet(buf)
            }
            else -> Log.w(TAG, "Unknown DocumentAttribute: 0x${typeId.toUInt().toString(16)}")
        }
    }

    private fun skipInputStickerSet(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xffb62b95.toInt() -> {} // empty
            0x9de7a269.toInt() -> { buf.int64(); buf.int64() } // ID
            0x861cc8a0.toInt() -> buf.string() // shortName
            0xe67f520e.toInt() -> buf.string() // dice: emoticon
            else -> {} // most other variants are parameterless
        }
    }

    // ---- WebPage ----

    private fun skipWebPage(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x211a1788.toInt() -> buf.int64() // webPageEmpty: id
            0xb0d13e47.toInt() -> { // webPagePending
                val flags = Fields.decode(buf)
                buf.int64() // id
                if (flags.has(0)) buf.string() // url
                buf.int32() // date
            }
            0xe89c45b2.toInt() -> { // webPage
                val flags = Fields.decode(buf)
                buf.int64() // id
                buf.string() // url
                buf.string() // display_url
                buf.int32() // hash
                if (flags.has(0)) buf.string() // type
                if (flags.has(1)) buf.string() // site_name
                if (flags.has(2)) buf.string() // title
                if (flags.has(3)) buf.string() // description
                if (flags.has(4)) skipBoxedType(buf) // photo
                if (flags.has(5)) { buf.string(); buf.string() } // embed_url embed_type
                if (flags.has(6)) { buf.int32(); buf.int32() } // embed_width embed_height
                if (flags.has(7)) buf.int32() // duration
                if (flags.has(8)) buf.string() // author
                if (flags.has(9)) skipBoxedType(buf) // document
                if (flags.has(10)) {
                    Log.w(TAG, "Skipping cached_page in webPage — complex type, may corrupt buffer")
                    skipBoxedType(buf)
                }
                if (flags.has(12)) skipVectorBoxed(buf) // attributes
            }
            0x7311ca11.toInt() -> {} // webPageNotModified (flags only if present)
            else -> Log.w(TAG, "Unknown WebPage: 0x${typeId.toUInt().toString(16)}")
        }
    }

    // ---- MessageEntity ----

    fun skipMessageEntity(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            // Simple: offset length
            0xbb92ba95.toInt(), // unknown
            0xfa04579d.toInt(), // mention
            0x6f635b0d.toInt(), // hashtag
            0x6cef8ac7.toInt(), // botCommand
            0x6ed02538.toInt(), // url
            0x64e475c2.toInt(), // email
            0xbd610bc9.toInt(), // bold
            0x826f8b60.toInt(), // italic
            0x28a20571.toInt(), // code
            0x9b69e34b.toInt(), // phone
            0x4c4e743f.toInt(), // cashtag
            0x9c4e7e8b.toInt(), // underline
            0xbf0693d4.toInt(), // strike
            0x32ca960f.toInt(), // spoiler
            0x761e6af4.toInt(), // bankCard
            -> { buf.int32(); buf.int32() }

            0x73924be0.toInt() -> { buf.int32(); buf.int32(); buf.string() } // pre: offset length language
            0x76a6d327.toInt() -> { buf.int32(); buf.int32(); buf.string() } // textUrl: offset length url
            0xdc7b1140.toInt() -> { buf.int32(); buf.int32(); buf.int64() } // mentionName: offset length user_id
            0xc8cf05f8.toInt() -> { buf.int32(); buf.int32(); buf.int64() } // customEmoji: offset length document_id
            0xf1ccaaac.toInt() -> { Fields.decode(buf); buf.int32(); buf.int32() } // blockquote: flags offset length

            else -> {
                Log.w(TAG, "Unknown MessageEntity 0x${typeId.toUInt().toString(16)}, assuming offset+length")
                buf.int32(); buf.int32()
            }
        }
    }

    // ---- ReplyMarkup ----

    fun skipReplyMarkup(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xa03e5b85.toInt() -> Fields.decode(buf) // replyKeyboardHide
            0x86b40b08.toInt() -> { // replyKeyboardForceReply
                val flags = Fields.decode(buf)
                if (flags.has(3)) buf.string() // placeholder
            }
            0x85dd99d1.toInt() -> { // replyKeyboardMarkup
                val flags = Fields.decode(buf)
                skipVector(buf) { skipKeyboardButtonRow(it) }
                if (flags.has(3)) buf.string() // placeholder
            }
            0x48a30254.toInt() -> { // replyInlineMarkup
                skipVector(buf) { skipKeyboardButtonRow(it) }
            }
            else -> Log.w(TAG, "Unknown ReplyMarkup: 0x${typeId.toUInt().toString(16)}")
        }
    }

    private fun skipKeyboardButtonRow(buf: TlBuffer) {
        buf.int32() // constructor 0x77608b83
        skipVector(buf) { skipKeyboardButton(it) }
    }

    private fun skipKeyboardButton(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xa2fa4880.toInt() -> buf.string() // keyboardButton: text
            0x258aff05.toInt() -> { buf.string(); buf.string() } // keyboardButtonUrl: text url
            0x35bbdb6b.toInt() -> { Fields.decode(buf); buf.string(); buf.bytes() } // callback: flags text data
            0xb16a6c29.toInt() -> buf.string() // requestPhone: text
            0xfc796b3f.toInt() -> buf.string() // requestGeoLocation: text
            0x93b9fbb5.toInt() -> { // switchInline
                val flags = Fields.decode(buf)
                buf.string(); buf.string() // text query
                if (flags.has(1)) skipVectorBoxed(buf) // peer_types
            }
            0x50f41ccf.toInt() -> buf.string() // game: text
            0xafd93fbb.toInt() -> buf.string() // buy: text
            0x10b78d29.toInt() -> { // urlAuth
                val flags = Fields.decode(buf)
                buf.string() // text
                if (flags.has(0)) buf.string() // fwd_text
                buf.string() // url
                buf.int32() // button_id
            }
            0xbbc7515d.toInt() -> { // requestPoll
                val flags = Fields.decode(buf)
                if (flags.has(0)) buf.int32() // quiz Bool
                buf.string() // text
            }
            0x13767230.toInt() -> { buf.string(); buf.string() } // webView: text url
            0xa0c0505c.toInt() -> { buf.string(); buf.string() } // simpleWebView: text url
            0x53d7bfd8.toInt() -> { // requestPeer
                buf.string(); buf.int32() // text button_id
                skipBoxedType(buf) // peer_type
                buf.int32() // max_quantity
            }
            else -> Log.w(TAG, "Unknown KeyboardButton: 0x${typeId.toUInt().toString(16)}")
        }
    }

    // ---- MessageReplies ----

    fun skipMessageReplies(buf: TlBuffer) {
        val typeId = buf.int32() // 0x83d60fc2
        val flags = Fields.decode(buf)
        buf.int32() // replies
        buf.int32() // replies_pts
        if (flags.has(1)) skipVector(buf) { decodePeer(it) } // recent_repliers
        if (flags.has(0)) buf.int64() // channel_id
        if (flags.has(2)) buf.int32() // max_id
        if (flags.has(3)) buf.int32() // read_max_id
    }

    // ---- Reactions ----

    fun skipReactions(buf: TlBuffer) {
        val typeId = buf.int32()
        if (typeId == 0x4f474992.toInt()) { // messageReactions
            val flags = Fields.decode(buf)
            skipVector(buf) { skipReactionCount(it) } // results
            if (flags.has(1)) skipVector(buf) { skipMessagePeerReaction(it) } // recent_reactions
        }
    }

    private fun skipReactionCount(buf: TlBuffer) {
        buf.int32() // constructor
        val flags = Fields.decode(buf)
        if (flags.has(0)) buf.int32() // chosen_order
        skipReaction(buf) // reaction
        buf.int32() // count
    }

    private fun skipReaction(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0x1b2286b8.toInt() -> buf.string() // reactionEmoji: emoticon
            0x8935fc73.toInt() -> buf.int64() // reactionCustomEmoji: document_id
            0x79f5d419.toInt() -> {} // reactionEmpty
            0x523f7fb5.toInt() -> {} // reactionPaid
            else -> Log.w(TAG, "Unknown Reaction: 0x${typeId.toUInt().toString(16)}")
        }
    }

    private fun skipMessagePeerReaction(buf: TlBuffer) {
        buf.int32() // constructor
        val flags = Fields.decode(buf)
        skipReaction(buf) // reaction
        buf.int32() // date
        decodePeer(buf) // peer_id
    }

    // ---- RestrictionReason ----

    fun skipRestrictionReason(buf: TlBuffer) {
        buf.int32() // constructor
        buf.string(); buf.string(); buf.string() // platform reason text
    }

    // ---- FactCheck ----

    fun skipFactCheck(buf: TlBuffer) {
        buf.int32() // constructor
        val flags = Fields.decode(buf)
        if (flags.has(1)) buf.string() // country
        if (flags.has(0)) { // text: TextWithEntities
            buf.int32() // TextWithEntities constructor
            buf.string() // text
            skipVector(buf) { skipMessageEntity(it) } // entities
        }
        buf.int64() // hash
    }

    // ---- MessageAction (for MessageService) ----

    fun skipMessageAction(buf: TlBuffer) {
        val typeId = buf.int32()
        when (typeId) {
            0xb6aef7b0.toInt() -> {} // messageActionEmpty
            0xbd47cbad.toInt() -> { // messageActionChatCreate
                buf.string() // title
                skipVector(buf) { it.int64() } // users
            }
            0xb5a1ce5a.toInt() -> buf.string() // messageActionChatEditTitle
            0x7fcb13a8.toInt() -> skipBoxedType(buf) // messageActionChatEditPhoto: photo
            0x95e3fbef.toInt() -> {} // messageActionChatDeletePhoto
            0x15cefd00.toInt() -> skipVector(buf) { it.int64() } // messageActionChatAddUser
            0xa43f30cc.toInt() -> buf.int64() // messageActionChatDeleteUser
            0x031224c3.toInt() -> buf.int64() // messageActionChatJoinedByLink: inviter_id
            0x95d2ac92.toInt() -> buf.string() // messageActionChannelCreate: title
            0xe1037f92.toInt() -> buf.int64() // messageActionChatMigrateTo: channel_id
            0xea3948e9.toInt() -> { buf.string(); buf.int64() } // messageActionChannelMigrateFrom
            0x94bd38ed.toInt() -> {} // messageActionPinMessage
            0x9fbab604.toInt() -> {} // messageActionHistoryClear
            0x80e11a7f.toInt() -> { // messageActionPhoneCall
                val flags = Fields.decode(buf)
                buf.int64() // call_id
                if (flags.has(0)) buf.int32() // reason (PhoneCallDiscardReason type id)
                if (flags.has(1)) buf.int32() // duration
            }
            0xf3f25f76.toInt() -> {} // messageActionContactSignUp
            0x3c134d7b.toInt() -> { // messageActionSetMessagesTTL
                val flags = Fields.decode(buf)
                buf.int32() // period
                if (flags.has(0)) buf.int64() // auto_setting_from
            }
            else -> Log.w(TAG, "Unknown MessageAction: 0x${typeId.toUInt().toString(16)}")
        }
    }
}
