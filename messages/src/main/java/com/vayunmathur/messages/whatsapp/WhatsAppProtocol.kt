package com.vayunmathur.messages.whatsapp

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import org.signal.libsignal.protocol.ecc.ECPublicKey

/**
 * WhatsApp Web protocol implementation.
 * Implements Noise_XX_25519_AESGCM_SHA256 handshake, binary XML encoding,
 * protobuf E2E message format, and media encryption.
 *
 * Reference: whatsmeow (github.com/tulir/whatsmeow)
 */
object WhatsAppProtocol {
    private const val TAG = "WhatsAppProtocol"

    const val WS_URL = "wss://web.whatsapp.com/ws/chat"
    const val WS_ORIGIN = "https://web.whatsapp.com"

    // Noise protocol pattern — 32 bytes, null-padded
    const val NOISE_START_PATTERN = "Noise_XX_25519_AESGCM_SHA256\u0000\u0000\u0000\u0000"

    // WA connection header: 'W', 'A', WAMagicValue(6), DictVersion(3)
    val WA_CONN_HEADER = byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 6, 3)

    // Frame constants (from whatsmeow/socket/constants.go)
    const val FRAME_MAX_SIZE = 1 shl 24
    const val FRAME_LENGTH_SIZE = 3

    // WhatsApp web message ID prefix
    private const val WEB_MESSAGE_ID_PREFIX = "3EB0"

    // WhatsApp web client version (from whatsmeow/store/clientpayload.go)
    val WA_VERSION = intArrayOf(2, 3000, 1040390703)

    // Media type keys for HKDF (from whatsmeow/download.go)
    const val MEDIA_KEY_IMAGE = "WhatsApp Image Keys"
    const val MEDIA_KEY_VIDEO = "WhatsApp Video Keys"
    const val MEDIA_KEY_AUDIO = "WhatsApp Audio Keys"
    const val MEDIA_KEY_DOCUMENT = "WhatsApp Document Keys"
    const val MEDIA_KEY_STICKER = "WhatsApp Image Keys"
    const val MEDIA_KEY_PTV = "WhatsApp Video Keys"
    const val MEDIA_KEY_HISTORY = "WhatsApp History Keys"
    const val MEDIA_KEY_APP_STATE = "WhatsApp App State Keys"
    const val MEDIA_KEY_STICKER_PACK = "WhatsApp Sticker Pack Keys"
    const val MEDIA_KEY_LINK_THUMBNAIL = "WhatsApp Link Thumbnail Keys"

    // WhatsApp certificate authority public key (Ed25519)
    val WA_CERT_PUBKEY = byteArrayOf(
        0x14, 0x23, 0x75, 0x57, 0x4d, 0x0a, 0x58, 0x71,
        0x66, 0xaa.toByte(), 0xe7.toByte(), 0x1e, 0xbe.toByte(), 0x51, 0x64, 0x37,
        0xc4.toByte(), 0xa2.toByte(), 0x8b.toByte(), 0x73, 0xe3.toByte(), 0x69, 0x5c, 0x6c,
        0xe1.toByte(), 0xf7.toByte(), 0xf9.toByte(), 0x54, 0x5d, 0xa8.toByte(), 0xee.toByte(), 0x6b
    )

    data class Node(
        val tag: String,
        val attrs: Map<String, String> = emptyMap(),
        val content: List<Node> = emptyList(),
        val data: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Node
            if (tag != other.tag) return false
            if (attrs != other.attrs) return false
            if (content != other.content) return false
            if (data != null) {
                if (other.data == null) return false
                if (!data.contentEquals(other.data)) return false
            } else if (other.data != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = tag.hashCode()
            result = 31 * result + attrs.hashCode()
            result = 31 * result + content.hashCode()
            result = 31 * result + (data?.contentHashCode() ?: 0)
            return result
        }

        fun getChildren(): List<Node> = content

        fun getChildByTag(tag: String): Node? = content.find { it.tag == tag }
    }

    /**
     * Noise Protocol Handshake State Machine
     * Implements Noise_XX_25519_AESGCM_SHA256 as per whatsmeow/socket/noisehandshake.go
     */
    class NoiseHandshake {
        private var hash = ByteArray(32)
        private var salt = ByteArray(32)
        private var key: SecretKeySpec? = null
        private var counter: UInt = 0u

        fun start(pattern: String, header: ByteArray) {
            val data = pattern.toByteArray(Charsets.UTF_8)
            hash = if (data.size == 32) {
                data
            } else {
                sha256(data)
            }
            salt = hash.copyOf()
            key = SecretKeySpec(hash, "AES")
            authenticate(header)
        }

        fun authenticate(data: ByteArray) {
            hash = sha256(hash + data)
        }

        fun encrypt(plaintext: ByteArray): ByteArray {
            val currentKey = key ?: throw IllegalStateException("Handshake not started")
            val iv = generateIV(counter)
            counter++

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, spec)
            cipher.updateAAD(hash)
            val ciphertext = cipher.doFinal(plaintext)
            authenticate(ciphertext)
            return ciphertext
        }

        fun decrypt(ciphertext: ByteArray): ByteArray {
            val currentKey = key ?: throw IllegalStateException("Handshake not started")
            val iv = generateIV(counter)
            counter++

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, currentKey, spec)
            cipher.updateAAD(hash)
            val plaintext = cipher.doFinal(ciphertext)
            authenticate(ciphertext)
            return plaintext
        }

        fun mixSharedSecretIntoKey(privateKey: ByteArray, publicKey: ByteArray) {
            val sharedSecret = x25519(privateKey, publicKey)
            mixIntoKey(sharedSecret)
        }

        fun mixIntoKey(data: ByteArray) {
            counter = 0u
            val (newSalt, newKey) = extractAndExpand(salt, data)
            salt = newSalt
            key = SecretKeySpec(newKey, "AES")
        }

        private fun extractAndExpand(salt: ByteArray, data: ByteArray): Pair<ByteArray, ByteArray> {
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(data, salt, null))

            val writeKey = ByteArray(32)
            val readKey = ByteArray(32)
            hkdf.generateBytes(writeKey, 0, 32)
            hkdf.generateBytes(readKey, 0, 32)

            return Pair(writeKey, readKey)
        }

        fun finish(): Pair<SecretKeySpec, SecretKeySpec> {
            val (writeKey, readKey) = extractAndExpand(salt, ByteArray(0))
            return Pair(
                SecretKeySpec(writeKey, "AES"),
                SecretKeySpec(readKey, "AES")
            )
        }

        private fun generateIV(counter: UInt): ByteArray {
            val iv = ByteArray(12)
            ByteBuffer.wrap(iv, 8, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(counter.toInt())
            return iv
        }
    }

    // -- Cryptography helpers --

    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val privParams = X25519PrivateKeyParameters(privateKey, 0)
        val pubParams = X25519PublicKeyParameters(publicKey, 0)
        val agreement = X25519Agreement()
        agreement.init(privParams)
        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(pubParams, sharedSecret, 0)
        return sharedSecret
    }

    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val random = SecureRandom()
        val privateKey = ByteArray(32)
        random.nextBytes(privateKey)
        privateKey[0] = (privateKey[0].toInt() and 248).toByte()
        privateKey[31] = (privateKey[31].toInt() and 127).toByte()
        privateKey[31] = (privateKey[31].toInt() or 64).toByte()

        val privParams = X25519PrivateKeyParameters(privateKey, 0)
        val publicKey = privParams.generatePublicKey().encoded
        return Pair(privateKey, publicKey)
    }

    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key, "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    /**
     * Expand a 32-byte app-state sync key into the 5 sub-keys via HKDF-SHA256 with info
     * "WhatsApp Mutation Keys" (160 bytes). Order: index, valueEncryption, valueMac,
     * snapshotMac, patchMac. Ref whatsmeow appstate/keys.go expandAppStateKeys.
     */
    fun expandAppStateKeys(keyData: ByteArray): Array<ByteArray> {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(keyData, null, "WhatsApp Mutation Keys".toByteArray(Charsets.UTF_8)))
        val out = ByteArray(160)
        hkdf.generateBytes(out, 0, 160)
        return arrayOf(
            out.copyOfRange(0, 32),
            out.copyOfRange(32, 64),
            out.copyOfRange(64, 96),
            out.copyOfRange(96, 128),
            out.copyOfRange(128, 160),
        )
    }

    /**
     * Decrypt an app-state mutation/record value blob: [iv(16)][ciphertext][valueMac(32)] using
     * the valueEncryption sub-key (AES-256-CBC). MAC is not verified. Ref whatsmeow decodeMutation.
     */
    fun decryptAppStateValue(valueBlob: ByteArray, valueEncryptionKey: ByteArray): ByteArray? {
        if (valueBlob.size < 16 + 32) return null
        val content = valueBlob.copyOfRange(0, valueBlob.size - 32) // strip 32-byte valueMac
        val iv = content.copyOfRange(0, 16)
        val ciphertext = content.copyOfRange(16, content.size)
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(valueEncryptionKey, "AES"), IvParameterSpec(iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Derive media encryption keys from a media key using HKDF.
     * Returns (iv, cipherKey, macKey, refKey) — each used in media encrypt/decrypt.
     * From whatsmeow/download.go getMediaKeys()
     */
    fun getMediaKeys(mediaKey: ByteArray, mediaType: String): MediaKeys {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(mediaKey, null, mediaType.toByteArray(Charsets.UTF_8)))
        val expanded = ByteArray(112)
        hkdf.generateBytes(expanded, 0, 112)
        return MediaKeys(
            iv = expanded.copyOfRange(0, 16),
            cipherKey = expanded.copyOfRange(16, 48),
            macKey = expanded.copyOfRange(48, 80),
            refKey = expanded.copyOfRange(80, 112)
        )
    }

    data class MediaKeys(
        val iv: ByteArray,
        val cipherKey: ByteArray,
        val macKey: ByteArray,
        val refKey: ByteArray,
    )

    /**
     * Encrypt media using AES-256-CBC + HMAC-SHA256.
     * From whatsmeow/upload.go Upload()
     */
    fun encryptMedia(plaintext: ByteArray, mediaType: String): MediaEncryptResult {
        val random = SecureRandom()
        val mediaKey = ByteArray(32).also { random.nextBytes(it) }
        val keys = getMediaKeys(mediaKey, mediaType)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keys.cipherKey, "AES")
        val ivSpec = IvParameterSpec(keys.iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val ciphertext = cipher.doFinal(plaintext)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keys.macKey, "HmacSHA256"))
        mac.update(keys.iv)
        mac.update(ciphertext)
        val macValue = mac.doFinal()

        val dataToUpload = ciphertext + macValue.copyOfRange(0, 10)
        val fileSha256 = sha256(plaintext)
        val fileEncSha256 = sha256(dataToUpload)

        return MediaEncryptResult(
            mediaKey = mediaKey,
            encryptedData = dataToUpload,
            fileSha256 = fileSha256,
            fileEncSha256 = fileEncSha256,
            fileLength = plaintext.size.toLong()
        )
    }

    data class MediaEncryptResult(
        val mediaKey: ByteArray,
        val encryptedData: ByteArray,
        val fileSha256: ByteArray,
        val fileEncSha256: ByteArray,
        val fileLength: Long,
    )

    /**
     * Decrypt downloaded media.
     * From whatsmeow/download.go
     */
    fun decryptMedia(ciphertextWithMac: ByteArray, mediaKey: ByteArray, mediaType: String): ByteArray {
        val keys = getMediaKeys(mediaKey, mediaType)

        val macOffset = ciphertextWithMac.size - 10
        val ciphertext = ciphertextWithMac.copyOfRange(0, macOffset)

        // Verify HMAC
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keys.macKey, "HmacSHA256"))
        mac.update(keys.iv)
        mac.update(ciphertext)
        val expectedMac = mac.doFinal().copyOfRange(0, 10)
        val actualMac = ciphertextWithMac.copyOfRange(macOffset, ciphertextWithMac.size)
        if (!expectedMac.contentEquals(actualMac)) {
            throw SecurityException("Media HMAC verification failed")
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(keys.cipherKey, "AES")
        val ivSpec = IvParameterSpec(keys.iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(ciphertext)
    }

    // -- Message ID generation --

    /**
     * Generate a message ID: "3EB0" + uppercase hex of sha256(unixSeconds ++ ownUser@c.us ++ random16)[:9].
     * From whatsmeow/send.go Client.GenerateMessageID().
     */
    fun generateMessageId(ownJid: String?): String {
        val buf = java.io.ByteArrayOutputStream()
        val ts = ByteArray(8)
        ByteBuffer.wrap(ts).putLong(System.currentTimeMillis() / 1000L)
        buf.write(ts)
        if (!ownJid.isNullOrEmpty()) {
            val user = ownJid.substringBefore('@').substringBefore(':')
            buf.write(user.toByteArray(Charsets.UTF_8))
            buf.write("@c.us".toByteArray(Charsets.UTF_8))
        }
        val rand = ByteArray(16)
        SecureRandom().nextBytes(rand)
        buf.write(rand)
        val hash = sha256(buf.toByteArray())
        val hex = hash.copyOfRange(0, 9).joinToString("") { "%02X".format(it) }
        return WEB_MESSAGE_ID_PREFIX + hex
    }

    // -- Message padding (Signal Protocol requirement) --

    /**
     * Pad message with random 1-15 bytes where each pad byte equals the pad count.
     * From whatsmeow/message.go padMessage(): random byte masked to 0x0f, 0 -> 15.
     */
    fun padMessage(plaintext: ByteArray): ByteArray {
        var padSize = SecureRandom().nextInt(16)
        if (padSize == 0) padSize = 15
        val padded = ByteArray(plaintext.size + padSize)
        System.arraycopy(plaintext, 0, padded, 0, plaintext.size)
        for (i in plaintext.size until padded.size) {
            padded[i] = padSize.toByte()
        }
        return padded
    }

    fun unpadMessage(padded: ByteArray): ByteArray {
        if (padded.isEmpty()) return padded
        val padSize = padded.last().toInt() and 0xFF
        if (padSize == 0 || padSize > padded.size) return padded
        return padded.copyOfRange(0, padded.size - padSize)
    }

    // -- Binary XML encoding/decoding (unchanged, already correct) --

    private object BinaryToken {
        const val LIST_EMPTY: Byte = 0
        const val DICTIONARY_0: Int = 236
        const val DICTIONARY_1: Int = 237
        const val DICTIONARY_2: Int = 238
        const val DICTIONARY_3: Int = 239
        const val LIST_8: Byte = 248.toByte()
        const val LIST_16: Byte = 249.toByte()
        const val JID_PAIR: Byte = 250.toByte()
        const val HEX_8: Int = 251
        const val BINARY_8: Byte = 252.toByte()
        const val BINARY_20: Byte = 253.toByte()
        const val BINARY_32: Byte = 254.toByte()
        const val NIBBLE_8: Int = 255
        const val PACKED_MAX = 127
        const val SINGLE_BYTE_MAX = 256

        val doubleByteTokens = arrayOf(
            arrayOf("read-self", "active", "fbns", "protocol", "reaction", "screen_width", "heartbeat", "deviceid", "2:47DEQpj8", "uploadfieldstat", "voip_settings", "retry", "priority", "longitude", "conflict", "false", "ig_professional", "replaced", "preaccept", "cover_photo", "uncompressed", "encopt", "ppic", "04", "passive", "status-revoke-drop", "keygen", "540", "offer", "rate", "opus", "latitude", "w:gp2", "ver", "4", "business_profile", "medium", "sender", "prev_v_id", "email", "website", "invited", "sign_credential", "05", "transport", "skey", "reason", "peer_abtest_bucket", "America/Sao_Paulo", "appid", "refresh", "100", "06", "404", "101", "104", "107", "102", "109", "103", "member_add_mode", "105", "transaction-id", "110", "106", "outgoing", "108", "111", "tokens", "followers", "ig_handle", "self_pid", "tue", "dec", "thu", "joinable", "peer_pid", "mon", "features", "wed", "peer_device_presence", "pn", "delete", "07", "fri", "audio_duration", "admin", "connected", "delta", "rcat", "disable", "collection", "08", "480", "sat", "phash", "all", "invite", "accept", "critical_unblock_low", "group_update", "signed_credential", "blinded_credential", "eph_setting", "net", "09", "background_location", "refresh_id", "Asia/Kolkata", "privacy_mode_ts", "account_sync", "voip_payload_type", "service_areas", "acs_public_key", "v_id", "0a", "fallback_class", "relay", "actual_actors", "metadata", "w:biz", "5", "connected-limit", "notice", "0b", "host_storage", "fb_page", "subject", "privatestats", "invis", "groupadd", "010", "note.m4r", "uuid", "0c", "8000", "sun", "372", "1020", "stage", "1200", "720", "canonical", "fb", "011", "video_duration", "0d", "1140", "superadmin", "012", "Opening.m4r", "keystore_attestation", "dleq_proof", "013", "timestamp", "ab_key", "w:sync:app:state", "0e", "vertical", "600", "p_v_id", "6", "likes", "014", "500", "1260", "creator", "0f", "rte", "destination", "group", "group_info", "syncd_anti_tampering_fatal_exception_enabled", "015", "dl_bw", "Asia/Jakarta", "vp8/h.264", "online", "1320", "fb:multiway", "10", "timeout", "016", "nse_retry", "urn:xmpp:whatsapp:dirty", "017", "a_v_id", "web_shops_chat_header_button_enabled", "nse_call", "inactive-upgrade", "none", "web", "groups", "2250", "mms_hot_content_timespan_in_seconds", "contact_blacklist", "nse_read", "suspended_group_deletion_notification", "binary_version", "018", "https://www.whatsapp.com/otp/copy/", "reg_push", "shops_hide_catalog_attachment_entrypoint", "server_sync", ".", "ephemeral_messages_allowed_values", "019", "mms_vcache_aggregation_enabled", "iphone", "America/Argentina/Buenos_Aires", "01a", "mms_vcard_autodownload_size_kb", "nse_ver", "shops_header_dropdown_menu_item", "dhash", "catalog_status", "communities_mvp_new_iqs_serverprop", "blocklist", "default", "11", "ephemeral_messages_enabled", "01b", "original_dimensions", "8", "mms4_media_retry_notification_encryption_enabled", "mms4_server_error_receipt_encryption_enabled", "original_image_url", "sync", "multiway", "420", "companion_enc_static", "shops_profile_drawer_entrypoint", "01c", "vcard_as_document_size_kb", "status_video_max_duration", "request_image_url", "01d", "regular_high", "s_t", "abt", "share_ext_min_preliminary_image_quality", "01e", "32", "syncd_key_rotation_enabled", "data_namespace", "md_downgrade_read_receipts2", "patch", "polltype", "ephemeral_messages_setting", "userrate", "15", "partial_pjpeg_bw_threshold", "played-self", "catalog_exists", "01f", "mute_v2"),
            arrayOf("reject", "dirty", "announcement", "020", "13", "9", "status_video_max_bitrate", "fb:thrift_iq", "offline_batch", "022", "full", "ctwa_first_business_reply_logging", "h.264", "smax_id", "group_description_length", "https://www.whatsapp.com/otp/code", "status_image_max_edge", "smb_upsell_business_profile_enabled", "021", "web_upgrade_to_md_modal", "14", "023", "s_o", "smaller_video_thumbs_status_enabled", "media_max_autodownload", "960", "blocking_status", "peer_msg", "joinable_group_call_client_version", "group_call_video_maximization_enabled", "return_snapshot", "high", "America/Mexico_City", "entry_point_block_logging_enabled", "pop", "024", "1050", "16", "1380", "one_tap_calling_in_group_chat_size", "regular_low", "inline_joinable_education_enabled", "hq_image_max_edge", "locked", "America/Bogota", "smb_biztools_deeplink_enabled", "status_image_quality", "1088", "025", "payments_upi_intent_transaction_limit", "voip", "w:g2", "027", "md_pin_chat_enabled", "026", "multi_scan_pjpeg_download_enabled", "shops_product_grid", "transaction_id", "ctwa_context_enabled", "20", "fna", "hq_image_quality", "alt_jpeg_doc_detection_quality", "group_call_max_participants", "pkey", "America/Belem", "image_max_kbytes", "web_cart_v1_1_order_message_changes_enabled", "ctwa_context_enterprise_enabled", "urn:xmpp:whatsapp:account", "840", "Asia/Kuala_Lumpur", "max_participants", "video_remux_after_repair_enabled", "stella_addressbook_restriction_type", "660", "900", "780", "context_menu_ios13_enabled", "mute-state", "ref", "payments_request_messages", "029", "frskmsg", "vcard_max_size_kb", "sample_buffer_gif_player_enabled", "match_last_seen", "510", "4983", "video_max_bitrate", "028", "w:comms:chat", "17", "frequently_forwarded_max", "groups_privacy_blacklist", "Asia/Karachi", "02a", "web_download_document_thumb_mms_enabled", "02b", "hist_sync", "biz_block_reasons_version", "1024", "18", "web_is_direct_connection_for_plm_transparent", "view_once_write", "file_max_size", "paid_convo_id", "online_privacy_setting", "video_max_edge", "view_once_read", "enhanced_storage_management", "multi_scan_pjpeg_encoding_enabled", "ctwa_context_forward_enabled", "video_transcode_downgrade_enable", "template_doc_mime_types", "hq_image_bw_threshold", "30", "body", "u_aud_limit_sil_restarts_ctrl", "other", "participating", "w:biz:directory", "1110", "vp8", "4018", "meta", "doc_detection_image_max_edge", "image_quality", "1170", "02c", "smb_upsell_chat_banner_enabled", "key_expiry_time_second", "pid", "stella_interop_enabled", "19", "linked_device_max_count", "md_device_sync_enabled", "02d", "02e", "360", "enhanced_block_enabled", "ephemeral_icon_in_forwarding", "paid_convo_status", "gif_provider", "project_name", "server-error", "canonical_url_validation_enabled", "wallpapers_v2", "syncd_clear_chat_delete_chat_enabled", "medianotify", "02f", "shops_required_tos_version", "vote", "reset_skey_on_id_change", "030", "image_max_edge", "multicast_limit_global", "ul_bw", "21", "25", "5000", "poll", "570", "22", "031", "1280", "WhatsApp", "032", "bloks_shops_enabled", "50", "upload_host_switching_enabled", "web_ctwa_context_compose_enabled", "ptt_forwarded_features_enabled", "unblocked", "partial_pjpeg_enabled", "fbid:devices", "height", "ephemeral_group_query_ts", "group_join_permissions", "order", "033", "alt_jpeg_status_quality", "migrate", "popular-bank", "win_uwp_deprecation_killswitch_enabled", "web_download_status_thumb_mms_enabled", "blocking", "url_text", "035", "web_forwarding_limit_to_groups", "1600", "val", "1000", "syncd_msg_date_enabled", "bank-ref-id", "max_subject", "payments_web_enabled", "web_upload_document_thumb_mms_enabled", "size", "request", "ephemeral", "24", "receipt_agg", "ptt_remember_play_position", "sampling_weight", "enc_rekey", "mute_always", "037", "034", "23", "036", "action", "click_to_chat_qr_enabled", "width", "disabled", "038", "md_blocklist_v2", "played_self_enabled", "web_buttons_message_enabled", "flow_id", "clear", "450", "fbid:thread", "bloks_session_state", "America/Lima", "attachment_picker_refresh", "download_host_switching_enabled", "1792", "u_aud_limit_sil_restarts_test2", "custom_urls", "device_fanout", "optimistic_upload", "2000", "key_cipher_suite", "web_smb_upsell_in_biz_profile_enabled", "e", "039", "siri_post_status_shortcut", "pair-device", "lg", "lc", "stream_attribution_url", "model", "mspjpeg_phash_gen", "catalog_send_all", "new_multi_vcards_ui", "share_biz_vcard_enabled", "-", "clean", "200", "md_blocklist_v2_server", "03b", "03a", "web_md_migration_experience", "ptt_conversation_waveform", "u_aud_limit_sil_restarts_test1"),
            arrayOf("64", "ptt_playback_speed_enabled", "web_product_list_message_enabled", "paid_convo_ts", "27", "manufacturer", "psp-routing", "grp_uii_cleanup", "ptt_draft_enabled", "03c", "business_initiated", "web_catalog_products_onoff", "web_upload_link_thumb_mms_enabled", "03e", "mediaretry", "35", "hfm_string_changes", "28", "America/Fortaleza", "max_keys", "md_mhfs_days", "streaming_upload_chunk_size", "5541", "040", "03d", "2675", "03f", "...", "512", "mute", "48", "041", "alt_jpeg_quality", "60", "042", "md_smb_quick_reply", "5183", "c", "1343", "40", "1230", "043", "044", "mms_cat_v1_forward_hot_override_enabled", "user_notice", "ptt_waveform_send", "047", "Asia/Calcutta", "250", "md_privacy_v2", "31", "29", "128", "md_messaging_enabled", "046", "crypto", "690", "045", "enc_iv", "75", "failure", "ptt_oot_playback", "AIzaSyDR5yfaG7OG8sMTUj8kfQEb8T9pN8BM6Lk", "w", "048", "2201", "web_large_files_ui", "Asia/Makassar", "812", "status_collapse_muted", "1334", "257", "2HP4dm", "049", "patches", "1290", "43cY6T", "America/Caracas", "web_sticker_maker", "campaign", "ptt_pausable_enabled", "33", "42", "attestation", "biz", "04b", "query_linked", "s", "125", "04a", "810", "availability", "1411", "responsiveness_v2_m1", "catalog_not_created", "34", "America/Santiago", "1465", "enc_p", "04d", "status_info", "04f", "key_version", "..", "04c", "04e", "md_group_notification", "1598", "1215", "web_cart_enabled", "37", "630", "1920", "2394", "-1", "vcard", "38", "elapsed", "36", "828", "peer", "pricing_category", "1245", "invalid", "stella_ios_enabled", "2687", "45", "1528", "39", "u_is_redial_audio_1104_ctrl", "1025", "1455", "58", "2524", "2603", "054", "bsp_system_message_enabled", "web_pip_redesign", "051", "verify_apps", "1974", "1272", "1322", "1755", "052", "70", "050", "1063", "1135", "1361", "80", "1096", "1828", "1851", "1251", "1921", "key_config_id", "1254", "1566", "1252", "2525", "critical_block", "1669", "max_available", "w:auth:backup:token", "product", "2530", "870", "1022", "participant_uuid", "web_cart_on_off", "1255", "1432", "1867", "41", "1415", "1440", "240", "1204", "1608", "1690", "1846", "1483", "1687", "1749", "69", "url_number", "053", "1325", "1040", "365", "59", "Asia/Riyadh", "1177", "test_recommended", "057", "1612", "43", "1061", "1518", "1635", "055", "1034", "1375", "750", "1430", "event_code", "1682", "503", "55", "865", "78", "1309", "1365", "44", "America/Guayaquil", "535", "LIMITED", "1377", "1613", "1420", "1599", "1822", "05a", "1681", "password", "1111", "1214", "1376", "1478", "47", "1082", "4282", "Europe/Istanbul", "1307", "46", "058", "1124", "256", "rate-overlimit", "retail", "u_a_socket_err_fix_succ_test", "1292", "1370", "1388", "520", "861", "psa", "regular", "1181", "1766", "05b", "1183", "1213", "1304", "1537"),
            arrayOf("1724", "profile_picture", "1071", "1314", "1605", "407", "990", "1710", "746", "pricing_model", "056", "059", "061", "1119", "6027", "65", "877", "1607", "05d", "917", "seen", "1516", "49", "470", "973", "1037", "1350", "1394", "1480", "1796", "keys", "794", "1536", "1594", "2378", "1333", "1524", "1825", "116", "309", "52", "808", "827", "909", "495", "1660", "361", "957", "google", "1357", "1565", "1967", "996", "1775", "586", "736", "1052", "1670", "bank", "177", "1416", "2194", "2222", "1454", "1839", "1275", "53", "997", "1629", "6028", "smba", "1378", "1410", "05c", "1849", "727", "create", "1559", "536", "1106", "1310", "1944", "670", "1297", "1316", "1762", "en", "1148", "1295", "1551", "1853", "1890", "1208", "1784", "7200", "05f", "178", "1283", "1332", "381", "643", "1056", "1238", "2024", "2387", "179", "981", "1547", "1705", "05e", "290", "903", "1069", "1285", "2436", "062", "251", "560", "582", "719", "56", "1700", "2321", "325", "448", "613", "777", "791", "51", "488", "902", "Asia/Almaty", "is_hidden", "1398", "1527", "1893", "1999", "2367", "2642", "237", "busy", "065", "067", "233", "590", "993", "1511", "54", "723", "860", "363", "487", "522", "605", "995", "1321", "1691", "1865", "2447", "2462", "NON_TRANSACTIONAL", "433", "871", "432", "1004", "1207", "2032", "2050", "2379", "2446", "279", "636", "703", "904", "248", "370", "691", "700", "1068", "1655", "2334", "060", "063", "364", "533", "534", "567", "1191", "1210", "1473", "1827", "069", "701", "2531", "514", "prev_dhash", "064", "496", "790", "1046", "1139", "1505", "1521", "1108", "207", "544", "637", "final", "1173", "1293", "1694", "1939", "1951", "1993", "2353", "2515", "504", "601", "857", "modify", "spam_request", "p_121_aa_1101_test4", "866", "1427", "1502", "1638", "1744", "2153", "068", "382", "725", "1704", "1864", "1990", "2003", "Asia/Dubai", "508", "531", "1387", "1474", "1632", "2307", "2386", "819", "2014", "066", "387", "1468", "1706", "2186", "2261", "471", "728", "1147", "1372", "1961")
        )

        private val doubleByteIndex: Map<String, Pair<Byte, Byte>> by lazy {
            val map = HashMap<String, Pair<Byte, Byte>>()
            for (dictIdx in doubleByteTokens.indices) {
                for (tokenIdx in doubleByteTokens[dictIdx].indices) {
                    val token = doubleByteTokens[dictIdx][tokenIdx]
                    if (token.isNotEmpty()) {
                        map[token] = Pair(dictIdx.toByte(), tokenIdx.toByte())
                    }
                }
            }
            map
        }

        const val INTEROP_JID: Int = 245
        const val FB_JID: Int = 246
        const val AD_JID: Int = 247

        val singleByteTokens = arrayOf("", "xmlstreamstart", "xmlstreamend", "s.whatsapp.net", "type", "participant", "from", "receipt", "id", "notification", "disappearing_mode", "status", "jid", "broadcast", "user", "devices", "device_hash", "to", "offline", "message", "result", "class", "xmlns", "duration", "notify", "iq", "t", "ack", "g.us", "enc", "urn:xmpp:whatsapp:push", "presence", "config_value", "picture", "verified_name", "config_code", "key-index-list", "contact", "mediatype", "routing_info", "edge_routing", "get", "read", "urn:xmpp:ping", "fallback_hostname", "0", "chatstate", "business_hours_config", "unavailable", "download_buckets", "skmsg", "verified_level", "composing", "handshake", "device-list", "media", "text", "fallback_ip4", "media_conn", "device", "creation", "location", "config", "item", "fallback_ip6", "count", "w:profile:picture", "image", "business", "2", "hostname", "call-creator", "display_name", "relaylatency", "platform", "abprops", "success", "msg", "offline_preview", "prop", "key-index", "v", "day_of_week", "pkmsg", "version", "1", "ping", "w:p", "download", "video", "set", "specific_hours", "props", "primary", "unknown", "hash", "commerce_experience", "last", "subscribe", "max_buckets", "call", "profile", "member_since_text", "close_time", "call-id", "sticker", "mode", "participants", "value", "query", "profile_options", "open_time", "code", "list", "host", "ts", "contacts", "upload", "lid", "preview", "update", "usync", "w:stats", "delivery", "auth_ttl", "context", "fail", "cart_enabled", "appdata", "category", "atn", "direct_connection", "decrypt-fail", "relay_id", "mmg-fallback.whatsapp.net", "target", "available", "name", "last_id", "mmg.whatsapp.net", "categories", "401", "is_new", "index", "tctoken", "ip4", "token_id", "latency", "recipient", "edit", "ip6", "add", "thumbnail-document", "26", "paused", "true", "identity", "stream:error", "key", "sidelist", "background", "audio", "3", "thumbnail-image", "biz-cover-photo", "cat", "gcm", "thumbnail-video", "error", "auth", "deny", "serial", "in", "registration", "thumbnail-link", "remove", "00", "gif", "thumbnail-gif", "tag", "capability", "multicast", "item-not-found", "description", "business_hours", "config_expo_key", "md-app-state", "expiration", "fallback", "ttl", "300", "md-msg-hist", "device_orientation", "out", "w:m", "open_24h", "side_list", "token", "inactive", "01", "document", "te2", "played", "encrypt", "msgr", "hide", "direct_path", "12", "state", "not-authorized", "url", "terminate", "signature", "status-revoke-delay", "02", "te", "linked_accounts", "trusted_contact", "timezone", "ptt", "kyc-id", "privacy_token", "readreceipts", "appointment_only", "address", "expected_ts", "privacy", "7", "android", "interactive", "device-identity", "enabled", "attribute_padding", "1080", "03", "screen_height")

        private val singleByteIndex: Map<String, Byte> by lazy {
            val map = HashMap<String, Byte>(singleByteTokens.size)
            for (i in singleByteTokens.indices) {
                if (singleByteTokens[i].isNotEmpty()) {
                    map[singleByteTokens[i]] = i.toByte()
                }
            }
            map
        }

        fun indexOfSingleToken(token: String): Int {
            return singleByteIndex[token]?.toInt()?.and(0xFF) ?: -1
        }

        fun indexOfDoubleByteToken(token: String): Triple<Int, Int, Boolean> {
            val pair = doubleByteIndex[token] ?: return Triple(0, 0, false)
            return Triple(pair.first.toInt() and 0xFF, pair.second.toInt() and 0xFF, true)
        }

        fun getDoubleToken(dictIndex: Int, tokenIndex: Int): String {
            if (dictIndex < 0 || dictIndex >= doubleByteTokens.size) return ""
            if (tokenIndex < 0 || tokenIndex >= doubleByteTokens[dictIndex].size) return ""
            return doubleByteTokens[dictIndex][tokenIndex]
        }
    }

    private class BinaryEncoder {
        private val data = mutableListOf<Byte>(0)

        fun getData(): ByteArray = data.toByteArray()

        private fun pushByte(b: Byte) { data.add(b) }
        private fun pushByte(b: Int) { data.add(b.toByte()) }
        private fun pushBytes(bytes: ByteArray) { bytes.forEach { data.add(it) } }

        private fun pushInt8(value: Int) { pushByte((value and 0xFF).toByte()) }
        private fun pushInt16(value: Int) {
            pushByte((value shr 8 and 0xFF).toByte())
            pushByte((value and 0xFF).toByte())
        }
        private fun pushInt20(value: Int) {
            pushByte(((value shr 16) and 0x0F).toByte())
            pushByte(((value shr 8) and 0xFF).toByte())
            pushByte((value and 0xFF).toByte())
        }
        private fun pushInt32(value: Int) {
            pushByte((value shr 24 and 0xFF).toByte())
            pushByte((value shr 16 and 0xFF).toByte())
            pushByte((value shr 8 and 0xFF).toByte())
            pushByte((value and 0xFF).toByte())
        }

        private fun writeByteLength(length: Int) {
            when {
                length < 256 -> { pushByte(BinaryToken.BINARY_8); pushInt8(length) }
                length < (1 shl 20) -> { pushByte(BinaryToken.BINARY_20); pushInt20(length) }
                else -> { pushByte(BinaryToken.BINARY_32); pushInt32(length) }
            }
        }

        fun writeNode(n: Node) {
            if (n.tag == "0") {
                pushByte(BinaryToken.LIST_8)
                pushByte(BinaryToken.LIST_EMPTY)
                return
            }

            val hasContent = if (n.data != null || n.content.isNotEmpty()) 1 else 0
            val attrCount = n.attrs.count { it.value.isNotEmpty() }
            writeListStart(2 * attrCount + 1 + hasContent)
            writeString(n.tag)
            writeAttributes(n.attrs)
            if (n.data != null) {
                writeBytes(n.data)
            } else if (n.content.isNotEmpty()) {
                writeListStart(n.content.size)
                for (child in n.content) {
                    writeNode(child)
                }
            }
        }

        private fun writeString(value: String) {
            val tokenIndex = BinaryToken.indexOfSingleToken(value)
            if (tokenIndex >= 0) {
                pushByte(tokenIndex)
            } else {
                val (dictIndex, tokIndex, found) = BinaryToken.indexOfDoubleByteToken(value)
                if (found) {
                    pushByte(BinaryToken.DICTIONARY_0 + dictIndex)
                    pushByte(tokIndex)
                } else if (validateNibble(value)) {
                    writePackedBytes(value, BinaryToken.NIBBLE_8)
                } else if (validateHex(value)) {
                    writePackedBytes(value, BinaryToken.HEX_8)
                } else {
                    writeStringRaw(value)
                }
            }
        }

        private fun writeBytes(value: ByteArray) {
            writeByteLength(value.size)
            pushBytes(value)
        }

        private fun writeStringRaw(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeByteLength(bytes.size)
            pushBytes(bytes)
        }

        /**
         * Encode a JID attribute value as a JID_PAIR (user@server) or AD_JID (user.agent:device)
         * token, matching WhatsApp's binary wire format. The server rejects stanzas (e.g. usync,
         * prekey fetch) whose jid attributes are written as raw strings.
         */
        private fun writeJid(jid: String) {
            val at = jid.indexOf('@')
            if (at < 0) { writeString(jid); return }
            val userPart = jid.substring(0, at)
            val server = jid.substring(at + 1)
            val colon = userPart.indexOf(':')
            val device = if (colon >= 0) userPart.substring(colon + 1).toIntOrNull() ?: 0 else 0
            val beforeColon = if (colon >= 0) userPart.substring(0, colon) else userPart
            val dot = beforeColon.indexOf('.')
            val agent = if (dot >= 0) beforeColon.substring(dot + 1).toIntOrNull() ?: 0 else 0
            val user = if (dot >= 0) beforeColon.substring(0, dot) else beforeColon
            if ((device != 0 || agent != 0) && server == "s.whatsapp.net") {
                pushByte(BinaryToken.AD_JID.toByte())
                pushByte(agent.toByte())
                pushByte(device.toByte())
                writeString(user)
            } else {
                pushByte(BinaryToken.JID_PAIR)
                if (user.isEmpty()) pushByte(BinaryToken.LIST_EMPTY) else writeString(user)
                writeString(server)
            }
        }

        private fun writeAttributes(attrs: Map<String, String>) {
            for ((key, value) in attrs) {
                if (value.isEmpty()) continue
                writeString(key)
                if (value.contains("@")) writeJid(value) else writeString(value)
            }
        }

        private fun writeListStart(size: Int) {
            when {
                size == 0 -> pushByte(BinaryToken.LIST_EMPTY)
                size < 256 -> { pushByte(BinaryToken.LIST_8); pushInt8(size) }
                else -> { pushByte(BinaryToken.LIST_16); pushInt16(size) }
            }
        }

        private fun validateNibble(value: String): Boolean {
            if (value.length > BinaryToken.PACKED_MAX) return false
            return value.all { it in '0'..'9' || it == '-' || it == '.' }
        }

        private fun validateHex(value: String): Boolean {
            if (value.length > BinaryToken.PACKED_MAX) return false
            // HEX_8 packing is uppercase-only; lowercase must fall through to raw
            // string encoding or it decodes back as uppercase (whatsmeow binary/encoder.go).
            return value.all { it in '0'..'9' || it in 'A'..'F' }
        }

        private fun writePackedBytes(value: String, dataType: Int) {
            pushByte(dataType)
            val roundedLength = ((value.length + 1) / 2)
            val flag = if (value.length % 2 != 0) (roundedLength or 128) else roundedLength
            pushByte(flag)
            val packer = if (dataType == BinaryToken.NIBBLE_8) ::packNibble else ::packHex
            var i = 0
            while (i < value.length / 2) {
                pushByte(((packer(value[2 * i]) shl 4) or packer(value[2 * i + 1])).toByte())
                i++
            }
            if (value.length % 2 != 0) {
                pushByte(((packer(value.last()) shl 4) or packer('\u0000')).toByte())
            }
        }

        private fun packNibble(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            '-' -> 10
            '.' -> 11
            '\u0000' -> 15
            else -> throw IllegalArgumentException("Invalid nibble char: $c")
        }

        private fun packHex(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'A'..'F' -> 10 + (c - 'A')
            in 'a'..'f' -> 10 + (c - 'a')
            '\u0000' -> 15
            else -> throw IllegalArgumentException("Invalid hex char: $c")
        }
    }

    private class BinaryDecoder(private val data: ByteArray) {
        private var index = 0

        private fun checkEOS(length: Int) {
            if (index + length > data.size) throw IllegalStateException("End of stream")
        }

        private fun readByte(): Int {
            checkEOS(1)
            return data[index++].toInt() and 0xFF
        }

        private fun readInt8(): Int = readByte()
        private fun readInt16(): Int {
            checkEOS(2)
            val v = ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
            index += 2
            return v
        }
        private fun readInt20(): Int {
            checkEOS(3)
            val v = ((data[index].toInt() and 0x0F) shl 16) or
                    ((data[index + 1].toInt() and 0xFF) shl 8) or
                    (data[index + 2].toInt() and 0xFF)
            index += 3
            return v
        }
        private fun readInt32(): Int {
            checkEOS(4)
            val v = ((data[index].toInt() and 0xFF) shl 24) or
                    ((data[index + 1].toInt() and 0xFF) shl 16) or
                    ((data[index + 2].toInt() and 0xFF) shl 8) or
                    (data[index + 3].toInt() and 0xFF)
            index += 4
            return v
        }

        private fun readRaw(length: Int): ByteArray {
            checkEOS(length)
            val result = data.copyOfRange(index, index + length)
            index += length
            return result
        }

        private fun readPacked8(tag: Int): String {
            val startByte = readByte()
            val sb = StringBuilder()
            for (i in 0 until (startByte and 127)) {
                val currByte = readByte()
                sb.append(unpackByte(tag, (currByte shr 4) and 0x0F))
                sb.append(unpackByte(tag, currByte and 0x0F))
            }
            var result = sb.toString()
            if ((startByte shr 7) != 0) result = result.dropLast(1)
            return result
        }

        private fun unpackByte(tag: Int, value: Int): Char = when (tag) {
            BinaryToken.NIBBLE_8 -> unpackNibble(value)
            BinaryToken.HEX_8 -> unpackHex(value)
            else -> throw IllegalArgumentException("Unknown packed tag: $tag")
        }
        private fun unpackNibble(value: Int): Char = when {
            value < 10 -> ('0' + value)
            value == 10 -> '-'
            value == 11 -> '.'
            value == 15 -> '\u0000'
            else -> throw IllegalArgumentException("Invalid nibble: $value")
        }
        private fun unpackHex(value: Int): Char = when {
            value < 10 -> ('0' + value)
            value < 16 -> ('A' + value - 10)
            else -> throw IllegalArgumentException("Invalid hex: $value")
        }

        private fun readListSize(tag: Int): Int = when (tag) {
            BinaryToken.LIST_EMPTY.toInt() and 0xFF -> 0
            BinaryToken.LIST_8.toInt() and 0xFF -> readInt8()
            BinaryToken.LIST_16.toInt() and 0xFF -> readInt16()
            else -> throw IllegalArgumentException("Unknown list tag: $tag")
        }

        private fun read(asString: Boolean): Any? {
            val tag = readByte()
            return when (tag) {
                BinaryToken.LIST_EMPTY.toInt() and 0xFF -> null
                BinaryToken.LIST_8.toInt() and 0xFF,
                BinaryToken.LIST_16.toInt() and 0xFF -> readList(tag)
                BinaryToken.BINARY_8.toInt() and 0xFF -> {
                    val size = readInt8()
                    if (asString) String(readRaw(size), Charsets.UTF_8) else readRaw(size)
                }
                BinaryToken.BINARY_20.toInt() and 0xFF -> {
                    val size = readInt20()
                    if (asString) String(readRaw(size), Charsets.UTF_8) else readRaw(size)
                }
                BinaryToken.BINARY_32.toInt() and 0xFF -> {
                    val size = readInt32()
                    if (asString) String(readRaw(size), Charsets.UTF_8) else readRaw(size)
                }
                in BinaryToken.DICTIONARY_0..BinaryToken.DICTIONARY_3 -> {
                    val idx = readInt8()
                    BinaryToken.getDoubleToken(tag - BinaryToken.DICTIONARY_0, idx)
                }
                BinaryToken.AD_JID -> {
                    val agent = readByte()
                    val device = readByte()
                    val user = read(true) as? String ?: ""
                    "$user.${agent}:${device}@s.whatsapp.net"
                }
                BinaryToken.FB_JID -> {
                    val user = read(true) as? String ?: ""
                    val device = readInt16()
                    val server = read(true) as? String ?: "msgr"
                    "$user:$device@$server"
                }
                BinaryToken.INTEROP_JID -> {
                    val user = read(true) as? String ?: ""
                    val device = readInt16()
                    val integrator = readInt16()
                    val server = read(true) as? String ?: ""
                    "$user:$device:$integrator@$server"
                }
                BinaryToken.JID_PAIR.toInt() and 0xFF -> {
                    val user = read(true) as? String
                    val server = read(true) as? String ?: throw IllegalStateException("JID missing server")
                    if (user != null) "$user@$server" else "@$server"
                }
                BinaryToken.NIBBLE_8, BinaryToken.HEX_8 -> readPacked8(tag)
                else -> {
                    if (tag in 1 until BinaryToken.singleByteTokens.size) {
                        BinaryToken.singleByteTokens[tag]
                    } else {
                        throw IllegalArgumentException("Invalid token $tag at position $index")
                    }
                }
            }
        }

        private fun readList(tag: Int): List<Node> {
            val size = readListSize(tag)
            return (0 until size).map { readNode() }
        }

        fun readNode(): Node {
            val listTag = readInt8()
            val listSize = readListSize(listTag)
            val tag = read(true) as? String ?: throw IllegalStateException("Node tag is not a string")
            if (listSize == 0 || tag.isEmpty()) throw IllegalStateException("Invalid node")

            val attrCount = (listSize - 1) shr 1
            val attrs = mutableMapOf<String, String>()
            for (i in 0 until attrCount) {
                val key = read(true) as? String ?: continue
                val value = read(true)
                attrs[key] = value?.toString() ?: ""
            }

            val content = mutableListOf<Node>()
            var nodeData: ByteArray? = null
            if (listSize % 2 == 0) {
                val contentData = read(false)
                when (contentData) {
                    is List<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        content.addAll(contentData as List<Node>)
                    }
                    is ByteArray -> nodeData = contentData
                    is String -> nodeData = contentData.toByteArray(Charsets.UTF_8)
                }
            }

            return Node(tag, attrs, content, nodeData)
        }
    }

    fun encodeNode(node: Node): ByteArray {
        val encoder = BinaryEncoder()
        encoder.writeNode(node)
        return encoder.getData()
    }

    fun decodeNode(data: ByteArray): Node {
        val decoder = BinaryDecoder(unpack(data))
        return decoder.readNode()
    }

    // WhatsApp Noise certificate root public key + issuer serial (whatsmeow/handshake.go).
    private val WA_CERT_PUB_KEY = byteArrayOf(
        0x14, 0x23, 0x75, 0x57, 0x4d, 0x0a, 0x58, 0x71, 0x66.toByte(), 0xaa.toByte(),
        0xe7.toByte(), 0x1e, 0xbe.toByte(), 0x51, 0x64, 0x37, 0xc4.toByte(), 0xa2.toByte(),
        0x8b.toByte(), 0x73, 0xe3.toByte(), 0x69, 0x5c, 0x6c, 0xe1.toByte(), 0xf7.toByte(),
        0xf9.toByte(), 0x54, 0x5d, 0xa8.toByte(), 0xee.toByte(), 0x6b,
    )
    private const val WA_CERT_ISSUER_SERIAL = 0

    /**
     * Verify the server's Noise certificate chain (intermediate signed by the WA root,
     * leaf signed by the intermediate, leaf key == server static key, validity window).
     * Port of whatsmeow/handshake.go verifyServerCert. Returns true if trusted.
     */
    fun verifyServerCert(certDecrypted: ByteArray, staticDecrypted: ByteArray): Boolean {
        return try {
            val chain = com.vayunmathur.messages.whatsapp.proto.WhatsAppCertProto.CertChain.parseFrom(certDecrypted)
            val interRaw = chain.intermediate.details.toByteArray()
            val interSig = chain.intermediate.signature.toByteArray()
            val leafRaw = chain.leaf.details.toByteArray()
            val leafSig = chain.leaf.signature.toByteArray()
            if (interRaw.isEmpty() || leafRaw.isEmpty() || interSig.size != 64 || leafSig.size != 64) {
                Log.e(TAG, "cert: missing/invalid parts")
                return false
            }
            if (!ECPublicKey.fromPublicKeyBytes(WA_CERT_PUB_KEY).verifySignature(interRaw, interSig)) {
                Log.e(TAG, "cert: intermediate signature invalid")
                return false
            }
            val inter = com.vayunmathur.messages.whatsapp.proto.WhatsAppCertProto.CertChain.NoiseCertificate.Details.parseFrom(interRaw)
            if (inter.issuerSerial != WA_CERT_ISSUER_SERIAL || inter.key.size() != 32) {
                Log.e(TAG, "cert: bad intermediate issuer/key")
                return false
            }
            if (!ECPublicKey.fromPublicKeyBytes(inter.key.toByteArray()).verifySignature(leafRaw, leafSig)) {
                Log.e(TAG, "cert: leaf signature invalid")
                return false
            }
            val leaf = com.vayunmathur.messages.whatsapp.proto.WhatsAppCertProto.CertChain.NoiseCertificate.Details.parseFrom(leafRaw)
            if (leaf.issuerSerial != inter.serial) {
                Log.e(TAG, "cert: leaf issuer serial mismatch")
                return false
            }
            if (!leaf.key.toByteArray().contentEquals(staticDecrypted)) {
                Log.e(TAG, "cert: leaf key != server static key")
                return false
            }
            val now = System.currentTimeMillis() / 1000
            for (d in listOf(inter, leaf)) {
                if (d.notBefore != 0L && now < d.notBefore) { Log.e(TAG, "cert: not yet valid"); return false }
                if (d.notAfter != 0L && now > d.notAfter) { Log.e(TAG, "cert: expired"); return false }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "cert verification error", e)
            false
        }
    }

    /**
     * Strips the leading compression flag byte from a decrypted frame and inflates
     * the remainder with zlib when the flag's 0x02 bit is set.
     * Ref: whatsmeow/binary/unpack.go Unpack().
     */
    private fun unpack(data: ByteArray): ByteArray {
        if (data.isEmpty()) throw IllegalStateException("empty frame, no flag byte")
        val flag = data[0].toInt() and 0xFF
        val payload = data.copyOfRange(1, data.size)
        return if (flag and 2 > 0) {
            val inflater = java.util.zip.Inflater()
            inflater.setInput(payload)
            val out = java.io.ByteArrayOutputStream(payload.size * 2)
            val buf = ByteArray(8192)
            try {
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0) {
                        if (inflater.finished() || inflater.needsDictionary()) break
                        if (inflater.needsInput()) throw IllegalStateException("zlib needs more input")
                    }
                    out.write(buf, 0, n)
                }
            } finally {
                inflater.end()
            }
            out.toByteArray()
        } else {
            payload
        }
    }

    // -- Message node builders (from whatsmeow/send.go) --

    /**
     * Build a text message node with E2E encrypted protobuf payload.
     * The enc node contains the Signal-encrypted E2E.Message protobuf.
     */
    /**
     * Build the E2E protobuf plaintext for a conversation (text) message.
     * The returned bytes are the unencrypted, unpadded waE2E.Message; the caller pads and
     * Signal-encrypts them before placing into an <enc> node.
     */
    fun buildConversationPlaintext(text: String): ByteArray {
        return buildConversationMessage(text).toByteArray()
    }

    /** Build the waE2E.Message proto object for a conversation (text) message. */
    fun buildConversationMessage(text: String): com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message {
        return com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setConversation(text)
            .build()
    }

    /**
     * Build a HISTORY_SYNC_ON_DEMAND peer-data-operation request. Sent E2E to our own account to
     * ask the primary to stream older messages for a chat. Ref whatsmeow BuildHistorySyncRequest.
     * Note: oldestMsgTimestampMs is actually seconds despite the field name.
     */
    fun buildHistoryOnDemandRequest(
        chatJid: String,
        oldestMsgId: String,
        oldestMsgFromMe: Boolean,
        oldestMsgTimestampSec: Long,
        count: Int,
    ): com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message {
        val req = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.HistorySyncOnDemandRequest.newBuilder()
            .setChatJid(chatJid)
            .setOldestMsgId(oldestMsgId)
            .setOldestMsgFromMe(oldestMsgFromMe)
            .setOnDemandMsgCount(count)
            .setOldestMsgTimestampMs(oldestMsgTimestampSec)
        val pdo = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PeerDataOperationRequestMessage.newBuilder()
            .setPeerDataOperationRequestType(3) // HISTORY_SYNC_ON_DEMAND
            .setHistorySyncOnDemandRequest(req)
        val proto = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.newBuilder()
            .setType(com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_MESSAGE)
            .setPeerDataOperationRequestMessage(pdo)
        return com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setProtocolMessage(proto)
            .build()
    }

    /**
     * Wrap a message in a DeviceSentMessage for fan-out to the sender's own other devices.
     * Ref whatsmeow send.go marshalMessage() dsmPlaintext.
     */
    fun deviceSentPlaintext(
        destinationJid: String,
        message: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message,
    ): ByteArray {
        return com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setDeviceSentMessage(
                com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.DeviceSentMessage.newBuilder()
                    .setDestinationJid(destinationJid)
                    .setMessage(message)
            )
            .build()
            .toByteArray()
    }

    /**
     * Build the SKDM-bearing plaintext that is 1:1 fanned out to every group device so they
     * can decrypt the group skmsg. Ref whatsmeow send.go sendGroup() skdMessage.
     */
    fun senderKeyDistributionPlaintext(groupJid: String, axolotlSkdm: ByteArray): ByteArray {
        return com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setSenderKeyDistributionMessage(
                com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.SenderKeyDistributionMessage.newBuilder()
                    .setGroupId(groupJid)
                    .setAxolotlSenderKeyDistributionMessage(com.google.protobuf.ByteString.copyFrom(axolotlSkdm))
            )
            .build()
            .toByteArray()
    }

    /**
     * Build an outgoing message node with a <participants> fan-out: one <to jid><enc> per
     * recipient/own device, plus an optional message-level extra <enc> (the group skmsg) and a
     * <device-identity> node when any per-device enc is a pkmsg. Mirrors whatsmeow
     * send.go prepareMessageNode/sendGroup.
     *
     * [participantEncs] is (wireDeviceJid, encType, ciphertext) per device.
     */
    data class ParticipantEnc(val deviceJid: String, val encType: String, val ciphertext: ByteArray)

    fun buildFanOutMessageNode(
        to: String,
        id: String,
        type: String,
        participantEncs: List<ParticipantEnc>,
        includeDeviceIdentity: Boolean,
        deviceIdentity: ByteArray?,
        extraEnc: Node? = null,
        extraEncAttrs: Map<String, String> = emptyMap(),
        messageAttrs: Map<String, String> = emptyMap(),
    ): Node {
        val toNodes = participantEncs.map { pe ->
            val encAttrs = mutableMapOf("v" to "2", "type" to pe.encType)
            encAttrs.putAll(extraEncAttrs)
            Node(
                tag = "to",
                attrs = mapOf("jid" to pe.deviceJid),
                content = listOf(Node(tag = "enc", attrs = encAttrs, data = pe.ciphertext)),
            )
        }
        val participants = Node(tag = "participants", content = toNodes)
        val content = mutableListOf(participants)
        if (extraEnc != null) content.add(extraEnc)
        if (includeDeviceIdentity && deviceIdentity != null) {
            content.add(Node(tag = "device-identity", data = deviceIdentity))
        }
        val attrs = mutableMapOf("to" to to, "id" to id, "type" to type)
        attrs.putAll(messageAttrs)
        return Node(tag = "message", attrs = attrs, content = content)
    }

    /**
     * Build a retry receipt sent when an inbound message fails to decrypt, asking the sender to
     * re-encrypt. Ref whatsmeow retry.go sendRetryReceipt. [keysNode] (identity + fresh prekeys +
     * device-identity) should be included on the 2nd+ retry or when forced.
     */
    fun buildRetryReceipt(
        originalNode: Node,
        registrationId: Int,
        retryCount: Int,
        keysNode: Node?,
    ): Node {
        val msgId = originalNode.attrs["id"] ?: ""
        val attrs = mutableMapOf(
            "id" to msgId,
            "to" to (originalNode.attrs["from"] ?: ""),
            "type" to "retry",
        )
        originalNode.attrs["recipient"]?.let { attrs["recipient"] = it }
        originalNode.attrs["participant"]?.let { attrs["participant"] = it }
        val regBytes = byteArrayOf(
            (registrationId ushr 24).toByte(),
            (registrationId ushr 16).toByte(),
            (registrationId ushr 8).toByte(),
            registrationId.toByte(),
        )
        val retryNode = Node(
            tag = "retry",
            attrs = mapOf(
                "count" to retryCount.toString(),
                "id" to msgId,
                "t" to (originalNode.attrs["t"] ?: ""),
                "v" to "1",
            ),
        )
        val content = mutableListOf(retryNode, Node(tag = "registration", data = regBytes))
        if (keysNode != null) content.add(keysNode)
        return Node(tag = "receipt", attrs = attrs, content = content)
    }

    /**
     * Build a group-info (participants) interactive query IQ.
     * Ref whatsmeow group.go getGroupInfo: <iq to=group xmlns=w:g2 type=get><query request=interactive/>.
     */
    fun buildGroupParticipantsQuery(groupJid: String, id: String): Node {
        return Node(
            tag = "iq",
            attrs = mapOf("id" to id, "type" to "get", "xmlns" to "w:g2", "to" to groupJid),
            content = listOf(Node(tag = "query", attrs = mapOf("request" to "interactive"))),
        )
    }

    /**
     * Build a usync device-list query for the given users.
     * Ref whatsmeow user.go GetUserDevices/usync: <iq xmlns=usync><usync mode=query context=message>
     * <query><devices version=2/></query><list><user jid=.../></list></usync>.
     */
    fun buildUsyncDevicesQuery(userJids: List<String>, id: String, sid: String): Node {
        val userNodes = userJids.map { Node(tag = "user", attrs = mapOf("jid" to it)) }
        return Node(
            tag = "iq",
            attrs = mapOf("id" to id, "type" to "get", "xmlns" to "usync", "to" to "s.whatsapp.net"),
            content = listOf(
                Node(
                    tag = "usync",
                    attrs = mapOf(
                        "sid" to sid,
                        "mode" to "query",
                        "last" to "true",
                        "index" to "0",
                        "context" to "message",
                    ),
                    content = listOf(
                        Node(
                            tag = "query",
                            content = listOf(Node(tag = "devices", attrs = mapOf("version" to "2"))),
                        ),
                        Node(tag = "list", content = userNodes),
                    ),
                )
            ),
        )
    }

    /**
     * Build a media_conn query IQ to obtain the upload auth token + hosts.
     * Ref whatsmeow mediaconn.go queryMediaConn: <iq xmlns=w:m type=set><media_conn/>.
     */
    fun buildMediaConnQuery(id: String): Node {
        return Node(
            tag = "iq",
            attrs = mapOf("id" to id, "type" to "set", "xmlns" to "w:m", "to" to "s.whatsapp.net"),
            content = listOf(Node(tag = "media_conn")),
        )
    }
    /**
     * Build a reaction as an E2E [Message] proto (ReactionMessage). Returned as a proto — NOT a
     * wire node — so the caller sends it through the normal Signal encryption + multi-device
     * fan-out path (buildEncryptedMessageNode / buildEncryptedGroupMessageNode) like any other
     * message. The reaction key must point at the TARGET message: [targetFromMe] reflects whether
     * the target was sent by us, and for group targets from someone else [targetSenderJid] is that
     * sender's JID (participant). Ref whatsmeow send.go BuildReaction + BuildMessageKey.
     * An empty [emoji] removes the reaction.
     */
    fun buildReactionProto(
        chatJid: String,
        targetMessageId: String,
        emoji: String,
        targetFromMe: Boolean,
        targetSenderJid: String?,
    ): com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message {
        val messageKey = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.MessageKey.newBuilder()
            .setFromMe(targetFromMe)
            .setId(targetMessageId)
            .setRemoteJid(chatJid)
        if (!targetFromMe && chatJid.contains("@g.us") && !targetSenderJid.isNullOrEmpty()) {
            messageKey.setParticipant(targetSenderJid)
        }

        val reactionMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ReactionMessage.newBuilder()
            .setKey(messageKey.build())
            .setText(emoji)
            .setSenderTimestampMs(System.currentTimeMillis())
            .build()

        return com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setReactionMessage(reactionMessage)
            .build()
    }

    /**
     * Build a receipt node with configurable type.
     * Supports: "read", "read-self", "played", "" (delivery), "inactive"
     * From whatsmeow/receipt.go
     */
    fun buildReceipt(
        chatJid: String,
        messageIds: List<String>,
        receiptType: String,
        senderJid: String? = null,
    ): Node {
        if (messageIds.isEmpty()) throw IllegalArgumentException("No message IDs")

        val attrs = mutableMapOf(
            "id" to messageIds.first(),
            "to" to chatJid
        )
        if (receiptType.isNotEmpty()) {
            attrs["type"] = receiptType
        }
        if (senderJid != null && chatJid.contains("@g.us")) {
            attrs["participant"] = senderJid
        }

        val children = mutableListOf<Node>()
        if (messageIds.size > 1) {
            val items = messageIds.drop(1).map { id ->
                Node(tag = "item", attrs = mapOf("id" to id))
            }
            children.add(Node(tag = "list", content = items))
        }

        return Node(tag = "receipt", attrs = attrs, content = children)
    }

    /**
     * Build a media message node.
     * From whatsmeow/send.go + upload.go
     */
    fun buildMediaProto(
        url: String,
        directPath: String,
        mediaKey: ByteArray,
        fileSha256: ByteArray,
        fileEncSha256: ByteArray,
        fileLength: Long,
        mimeType: String,
        caption: String?,
        mediaType: String, // "image", "video", "audio", "document", "sticker"
    ): com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message {
        val e2eBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()

        when (mediaType) {
            "image" -> {
                val imgBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ImageMessage.newBuilder()
                    .setUrl(url)
                    .setDirectPath(directPath)
                    .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
                    .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
                    .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
                    .setFileLength(fileLength.toULong().toLong())
                    .setMimetype(mimeType)
                if (caption != null) imgBuilder.setCaption(caption)
                e2eBuilder.setImageMessage(imgBuilder.build())
            }
            "video" -> {
                val vidBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.VideoMessage.newBuilder()
                    .setUrl(url)
                    .setDirectPath(directPath)
                    .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
                    .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
                    .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
                    .setFileLength(fileLength.toULong().toLong())
                    .setMimetype(mimeType)
                if (caption != null) vidBuilder.setCaption(caption)
                e2eBuilder.setVideoMessage(vidBuilder.build())
            }
            "audio" -> {
                val audBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.AudioMessage.newBuilder()
                    .setUrl(url)
                    .setDirectPath(directPath)
                    .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
                    .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
                    .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
                    .setFileLength(fileLength.toULong().toLong())
                    .setMimetype(mimeType)
                e2eBuilder.setAudioMessage(audBuilder.build())
            }
            "document" -> {
                val docBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.DocumentMessage.newBuilder()
                    .setUrl(url)
                    .setDirectPath(directPath)
                    .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
                    .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
                    .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
                    .setFileLength(fileLength.toULong().toLong())
                    .setMimetype(mimeType)
                e2eBuilder.setDocumentMessage(docBuilder.build())
            }
            "sticker" -> {
                val stickerBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.StickerMessage.newBuilder()
                    .setUrl(url)
                    .setDirectPath(directPath)
                    .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
                    .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
                    .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
                    .setFileLength(fileLength.toULong().toLong())
                    .setMimetype(mimeType)
                e2eBuilder.setStickerMessage(stickerBuilder.build())
            }
        }

        val plaintext = e2eBuilder.build()
        return plaintext
    }

    /**
     * Build a read receipt node.
     * From whatsmeow/receipt.go MarkRead()
     */
    fun buildReadReceipt(
        chatJid: String,
        messageIds: List<String>,
        senderJid: String? = null,
        timestamp: Long = System.currentTimeMillis() / 1000,
    ): Node {
        if (messageIds.isEmpty()) throw IllegalArgumentException("No message IDs")

        val attrs = mutableMapOf(
            "id" to messageIds.first(),
            "type" to "read",
            "to" to chatJid,
            "t" to timestamp.toString()
        )
        if (senderJid != null && chatJid.contains("@g.us")) {
            attrs["participant"] = senderJid
        }

        val children = mutableListOf<Node>()
        if (messageIds.size > 1) {
            val items = messageIds.drop(1).map { id ->
                Node(tag = "item", attrs = mapOf("id" to id))
            }
            children.add(Node(tag = "list", content = items))
        }

        return Node(tag = "receipt", attrs = attrs, content = children)
    }

    /**
     * Build an edit message node.
     * From whatsmeow/send.go BuildEdit()
     * Go wraps in EditedMessage -> FutureProofMessage -> Message -> ProtocolMessage
     */
    fun buildEditMessage(
        chatJid: String,
        targetMessageId: String,
        newText: String,
        ownJid: String,
        id: String,
    ): Node {
        val messageKey = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.MessageKey.newBuilder()
            .setFromMe(true)
            .setId(targetMessageId)
            .setRemoteJid(chatJid)
            .build()

        val newContent = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setConversation(newText)
            .build()

        val protocolMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.newBuilder()
            .setType(com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.MESSAGE_EDIT)
            .setKey(messageKey)
            .setEditedMessage(newContent)
            .setTimestampMs(System.currentTimeMillis())
            .build()

        val innerMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setProtocolMessage(protocolMessage)
            .build()

        val futureProof = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.FutureProofMessage.newBuilder()
            .setMessage(innerMessage)
            .build()

        val e2eMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setEditedMessage(futureProof)
            .build()
        val plaintext = e2eMessage.toByteArray()

        return Node(
            tag = "message",
            attrs = mapOf(
                "to" to chatJid,
                "id" to id,
                "type" to "text",
                "edit" to "1"
            ),
            content = listOf(
                Node(
                    tag = "enc",
                    attrs = mapOf("v" to "2", "type" to "msg", "decrypt-fail" to "hide"),
                    data = padMessage(plaintext)
                )
            )
        )
    }

    /**
     * Build a revoke (delete) message node.
     * From whatsmeow/send.go BuildRevoke()
     */
    fun buildRevokeMessage(
        chatJid: String,
        senderJid: String,
        targetMessageId: String,
        ownJid: String,
        id: String,
    ): Node {
        val isFromMe = senderJid.isEmpty() || senderJid == ownJid ||
            senderJid.substringBefore("@") == ownJid.substringBefore("@")
        val messageKey = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.MessageKey.newBuilder()
            .setFromMe(isFromMe)
            .setId(targetMessageId)
            .setRemoteJid(chatJid)
        if (!isFromMe && chatJid.contains("@g.us")) {
            messageKey.setParticipant(senderJid)
        }

        val protocolMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.newBuilder()
            .setType(com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.REVOKE)
            .setKey(messageKey.build())
            .build()

        val e2eMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setProtocolMessage(protocolMessage)
            .build()
        val plaintext = e2eMessage.toByteArray()

        return Node(
            tag = "message",
            attrs = mapOf(
                "to" to chatJid,
                "id" to id,
                "type" to "text",
                "edit" to if (isFromMe) "7" else "8"
            ),
            content = listOf(
                Node(
                    tag = "enc",
                    attrs = mapOf("v" to "2", "type" to "msg", "decrypt-fail" to "hide"),
                    data = padMessage(plaintext)
                )
            )
        )
    }

    /**
     * Build a chat presence (typing indicator) node.
     * From whatsmeow/send.go SendChatPresence()
     */
    fun buildChatPresence(
        chatJid: String,
        isComposing: Boolean,
        isAudio: Boolean = false,
        ownJid: String = "",
    ): Node {
        val state = if (isComposing) "composing" else "paused"
        val childAttrs = if (isComposing && isAudio) mapOf("media" to "audio") else emptyMap()
        val attrs = mutableMapOf("to" to chatJid)
        if (ownJid.isNotEmpty()) attrs["from"] = ownJid
        return Node(
            tag = "chatstate",
            attrs = attrs,
            content = listOf(
                Node(tag = state, attrs = childAttrs)
            )
        )
    }

    /**
     * Build a keepalive (ping) IQ node.
     * From whatsmeow/keepalive.go
     */
    fun buildKeepalive(id: String): Node {
        return Node(
            tag = "iq",
            attrs = mapOf(
                "id" to id,
                "xmlns" to "w:p",
                "type" to "get",
                "to" to "s.whatsapp.net"
            )
        )
    }

    /**
     * Build an ack node for acknowledging received messages.
     * From whatsmeow/receipt.go sendAck()
     */
    fun buildAck(
        nodeClass: String,
        nodeId: String,
        from: String,
        participant: String? = null,
        recipient: String? = null,
        type: String? = null,
    ): Node {
        val attrs = mutableMapOf(
            "class" to nodeClass,
            "id" to nodeId,
            "to" to from
        )
        if (participant != null) attrs["participant"] = participant
        if (recipient != null) attrs["recipient"] = recipient
        if (type != null && nodeClass != "message") attrs["type"] = type
        return Node(tag = "ack", attrs = attrs)
    }

    // -- Frame helpers --

    /**
     * Build a framed message with optional header and 3-byte big-endian length prefix.
     * From whatsmeow/socket/framesocket.go SendFrame()
     */
    fun buildFramedMessage(data: ByteArray, header: ByteArray?): ByteArray {
        val headerLength = header?.size ?: 0
        val dataLength = data.size
        if (dataLength >= FRAME_MAX_SIZE) {
            throw IllegalArgumentException("Frame too large: $dataLength bytes (max $FRAME_MAX_SIZE)")
        }
        val frame = ByteArray(headerLength + FRAME_LENGTH_SIZE + dataLength)

        var offset = 0
        if (header != null) {
            System.arraycopy(header, 0, frame, offset, headerLength)
            offset += headerLength
        }
        frame[offset] = (dataLength shr 16).toByte()
        frame[offset + 1] = (dataLength shr 8).toByte()
        frame[offset + 2] = dataLength.toByte()
        offset += FRAME_LENGTH_SIZE
        System.arraycopy(data, 0, frame, offset, dataLength)
        return frame
    }

    /**
     * Extract frame payload from raw data (strip 3-byte length prefix).
     * From whatsmeow/socket/framesocket.go processData()
     */
    fun extractFrame(data: ByteArray): ByteArray {
        if (data.size < FRAME_LENGTH_SIZE) return data
        val length = ((data[0].toInt() and 0xFF) shl 16) or
                ((data[1].toInt() and 0xFF) shl 8) or
                (data[2].toInt() and 0xFF)
        if (data.size < FRAME_LENGTH_SIZE + length) return data
        return data.copyOfRange(FRAME_LENGTH_SIZE, FRAME_LENGTH_SIZE + length)
    }

    // -- Message parsing --

    private fun formatDisappearingTimer(seconds: Int): String {
        return when {
            seconds >= 86400 * 90 -> "90 days"
            seconds >= 86400 * 7 -> "7 days"
            seconds >= 86400 -> "${seconds / 86400} days"
            seconds >= 3600 -> "${seconds / 3600} hours"
            seconds >= 60 -> "${seconds / 60} minutes"
            else -> "$seconds seconds"
        }
    }

    private fun extractContextInfo(
        e2eMessage: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message
    ): ContextInfoResult {
        val ctx = when {
            e2eMessage.hasExtendedTextMessage() -> e2eMessage.extendedTextMessage.contextInfo
            e2eMessage.hasImageMessage() -> e2eMessage.imageMessage.contextInfo
            e2eMessage.hasVideoMessage() -> e2eMessage.videoMessage.contextInfo
            e2eMessage.hasAudioMessage() -> e2eMessage.audioMessage.contextInfo
            e2eMessage.hasDocumentMessage() -> e2eMessage.documentMessage.contextInfo
            e2eMessage.hasStickerMessage() -> e2eMessage.stickerMessage.contextInfo
            e2eMessage.hasLocationMessage() -> e2eMessage.locationMessage.contextInfo
            e2eMessage.hasContactMessage() -> e2eMessage.contactMessage.contextInfo
            else -> null
        } ?: return ContextInfoResult()

        return ContextInfoResult(
            isForwarded = ctx.isForwarded,
            forwardingScore = ctx.forwardingScore,
            replyToId = ctx.stanzaId.ifEmpty { null },
            mentionedJids = ctx.mentionedJidList.orEmpty(),
        )
    }

    /**
     * Build a native PollCreationMessage proto. Mirrors whatsmeow BuildPollCreation: only
     * optionName is set per option (option hashes are computed later at vote time), and the poll's
     * shared secret is carried in the sibling MessageContextInfo.messageSecret (encKey is left
     * unset). [selectableCount] clamps to 0 (unlimited) when negative or greater than the option
     * count. Returned as a Message proto so the caller routes it through the normal fan-out.
     */
    fun buildPollCreationProto(
        name: String,
        options: List<String>,
        selectableCount: Int,
        messageSecret: ByteArray,
    ): com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message {
        val poll = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PollCreationMessage.newBuilder()
            .setName(name)
            .setSelectableOptionsCount(
                if (selectableCount < 0 || selectableCount > options.size) 0 else selectableCount
            )
        for (opt in options) {
            poll.addOptions(
                com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PollCreationMessage.Option.newBuilder()
                    .setOptionName(opt)
            )
        }
        val ctx = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.MessageContextInfo.newBuilder()
            .setMessageSecret(com.google.protobuf.ByteString.copyFrom(messageSecret))
        return com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setPollCreationMessageV3(poll.build())
            .setMessageContextInfo(ctx.build())
            .build()
    }

    /**
     * Build a LocationMessage proto (lat/long + optional name/address). Returned as a Message
     * proto so the caller can route it through the normal multi-device Signal fan-out
     * (buildEncryptedMessageNode), matching whatsmeow from-matrix.go location handling.
     */
    fun buildLocationProto(
        latitude: Double,
        longitude: Double,
        name: String? = null,
        address: String? = null,
    ): com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message {
        val locBuilder = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.LocationMessage.newBuilder()
            .setDegreesLatitude(latitude)
            .setDegreesLongitude(longitude)
        if (!name.isNullOrEmpty()) locBuilder.setName(name)
        if (!address.isNullOrEmpty()) locBuilder.setAddress(address)

        return com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setLocationMessage(locBuilder.build())
            .build()
    }

    /**
     * Build a contact/vCard message.
     * From whatsmeow wa-contact.go
     */
    fun buildContactMessage(
        chatJid: String,
        displayName: String,
        vcard: String,
        id: String,
    ): Node {
        val contactMsg = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ContactMessage.newBuilder()
            .setDisplayName(displayName)
            .setVcard(vcard)
            .build()

        val e2eMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setContactMessage(contactMsg)
            .build()

        val plaintext = e2eMessage.toByteArray()
        return Node(
            tag = "message",
            attrs = mapOf("to" to chatJid, "id" to id, "type" to "text"),
            content = listOf(
                Node(tag = "enc", attrs = mapOf("v" to "2", "type" to "msg"), data = padMessage(plaintext))
            )
        )
    }

    /**
     * Build a disappearing timer change message.
     * From whatsmeow handlematrix.go HandleMatrixDisappearingTimer()
     * Allowed values: 0 (off), 86400 (24h), 604800 (7d), 7776000 (90d)
     */
    fun buildDisappearingTimerMessage(
        chatJid: String,
        timerSeconds: Long,
        id: String,
    ): Node {
        val protocolMsg = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.newBuilder()
            .setType(com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.EPHEMERAL_SETTING)
            .setEphemeralExpiration(timerSeconds.toInt())
            .build()

        val e2eMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setProtocolMessage(protocolMsg)
            .build()

        val plaintext = e2eMessage.toByteArray()
        return Node(
            tag = "message",
            attrs = mapOf("to" to chatJid, "id" to id, "type" to "text"),
            content = listOf(
                Node(tag = "enc", attrs = mapOf("v" to "2", "type" to "msg"), data = padMessage(plaintext))
            )
        )
    }

    /**
     * Build a group info change IQ node.
     * From whatsmeow group.go SetGroupName/SetGroupTopic
     */
    fun buildGroupInfoChange(
        groupJid: String,
        field: String,
        value: String,
        id: String,
        extraAttrs: Map<String, String> = emptyMap(),
    ): Node {
        val childAttrs = mutableMapOf<String, String>()
        childAttrs.putAll(extraAttrs)
        return Node(
            tag = "iq",
            attrs = mapOf(
                "id" to id,
                "type" to "set",
                "xmlns" to "w:g2",
                "to" to groupJid,
            ),
            content = listOf(
                Node(tag = field, attrs = childAttrs, content = listOf(), data = value.toByteArray(Charsets.UTF_8))
            ),
        )
    }

    /**
     * Build a "set group topic/description" IQ.
     * From whatsmeow group.go SetGroupTopic: <description id=newID [prev=previousID]
     * [delete=true]> with a <body> child carrying the topic (no body when empty).
     */
    fun buildSetGroupTopic(
        groupJid: String,
        topic: String,
        newId: String,
        previousId: String? = null,
    ): Node {
        val descAttrs = mutableMapOf("id" to newId)
        if (!previousId.isNullOrEmpty()) descAttrs["prev"] = previousId
        val content = if (topic.isEmpty()) {
            descAttrs["delete"] = "true"
            emptyList()
        } else {
            listOf(Node(tag = "body", data = topic.toByteArray(Charsets.UTF_8)))
        }
        return Node(
            tag = "iq",
            attrs = mapOf(
                "id" to newId,
                "type" to "set",
                "xmlns" to "w:g2",
                "to" to groupJid,
            ),
            content = listOf(Node(tag = "description", attrs = descAttrs, content = content)),
        )
    }

    /**
     * Build a "leave group" IQ.
     * From whatsmeow group.go LeaveGroup: iq to the group server (g.us) with
     * <leave><group id=<groupJid>/></leave>.
     */
    fun buildLeaveGroup(groupJid: String, id: String): Node {
        return Node(
            tag = "iq",
            attrs = mapOf(
                "id" to id,
                "type" to "set",
                "xmlns" to "w:g2",
                "to" to "g.us",
            ),
            content = listOf(
                Node(
                    tag = "leave",
                    content = listOf(Node(tag = "group", attrs = mapOf("id" to groupJid))),
                ),
            ),
        )
    }

    /**
     * Build a group participant change IQ node.
     * From whatsmeow group.go UpdateGroupParticipants
     */
    fun buildGroupParticipantChange(
        groupJid: String,
        participantJids: List<String>,
        action: String,
        id: String,
    ): Node {
        val participants = participantJids.map { jid ->
            Node(tag = "participant", attrs = mapOf("jid" to jid))
        }
        return Node(
            tag = "iq",
            attrs = mapOf(
                "id" to id,
                "type" to "set",
                "xmlns" to "w:g2",
                "to" to groupJid,
            ),
            content = listOf(
                Node(tag = action, content = participants)
            ),
        )
    }

    /**
     * Build an encrypted poll vote (PollUpdateMessage) as a Message proto, routed through the
     * normal send path. Selected options are SHA-256 hashes of the chosen option names; the vote
     * payload is AES-256-GCM encrypted with a key derived from the poll's messageSecret.
     * Ref whatsmeow msgsecret.go BuildPollVote / EncryptPollVote.
     */
    fun buildPollVoteMessage(
        chatJid: String,
        pollMessageId: String,
        pollCreatorJid: String,
        pollFromMe: Boolean,
        voterJid: String,
        optionHashes: List<ByteArray>,
        pollSecret: ByteArray,
    ): com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message {
        val vote = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PollVoteMessage.newBuilder()
        optionHashes.forEach { vote.addSelectedOptions(com.google.protobuf.ByteString.copyFrom(it)) }
        val plaintext = vote.build().toByteArray()

        val (key, aad) = pollVoteKeyAndAad(pollSecret, pollMessageId, pollCreatorJid, voterJid)
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val ciphertext = aesGcm(Cipher.ENCRYPT_MODE, key, iv, plaintext, aad)

        val msgKey = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.MessageKey.newBuilder()
            .setFromMe(pollFromMe)
            .setId(pollMessageId)
            .setRemoteJid(chatJid)
        if (!pollFromMe && chatJid.contains("@g.us") && pollCreatorJid.isNotEmpty()) {
            msgKey.setParticipant(pollCreatorJid)
        }
        val update = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PollUpdateMessage.newBuilder()
            .setPollCreationMessageKey(msgKey.build())
            .setVote(
                com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PollEncValue.newBuilder()
                    .setEncPayload(com.google.protobuf.ByteString.copyFrom(ciphertext))
                    .setEncIV(com.google.protobuf.ByteString.copyFrom(iv))
            )
            .setSenderTimestampMS(System.currentTimeMillis())
        return com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
            .setPollUpdateMessage(update.build())
            .build()
    }

    /**
     * Decrypt an incoming poll vote, returning the SHA-256 option hashes the voter selected (or
     * null on failure). The caller maps hashes back to option names via the stored poll options.
     */
    fun decryptPollVote(
        update: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PollUpdateMessage,
        pollMessageId: String,
        pollCreatorJid: String,
        voterJid: String,
        pollSecret: ByteArray,
    ): List<ByteArray>? {
        return try {
            val (key, aad) = pollVoteKeyAndAad(pollSecret, pollMessageId, pollCreatorJid, voterJid)
            val plaintext = aesGcm(
                Cipher.DECRYPT_MODE, key,
                update.vote.encIV.toByteArray(),
                update.vote.encPayload.toByteArray(),
                aad,
            )
            com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PollVoteMessage
                .parseFrom(plaintext)
                .selectedOptionsList
                .map { it.toByteArray() }
        } catch (e: Exception) {
            Log.w(TAG, "Poll vote decrypt failed", e)
            null
        }
    }

    /**
     * Derive the poll-vote key + GCM additional-data, matching whatsmeow generateMsgSecretKey for
     * the "Poll Vote" use case:
     *   key = HKDF-SHA256(pollSecret, salt=nil, info = pollMsgId + creatorJid + voterJid + "Poll Vote")
     *   aad = pollMsgId + 0x00 + voterJid
     * JIDs are normalized to bare user@server (no device/agent) to match the peer.
     */
    private fun pollVoteKeyAndAad(
        pollSecret: ByteArray,
        pollMessageId: String,
        pollCreatorJid: String,
        voterJid: String,
    ): Pair<ByteArray, ByteArray> {
        val creator = normalizeJidForSecret(pollCreatorJid)
        val voter = normalizeJidForSecret(voterJid)
        val info = pollMessageId.toByteArray(Charsets.UTF_8) +
            creator.toByteArray(Charsets.UTF_8) +
            voter.toByteArray(Charsets.UTF_8) +
            "Poll Vote".toByteArray(Charsets.UTF_8)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(pollSecret, null, info))
        val key = ByteArray(32)
        hkdf.generateBytes(key, 0, 32)
        val aad = pollMessageId.toByteArray(Charsets.UTF_8) +
            byteArrayOf(0) +
            voter.toByteArray(Charsets.UTF_8)
        return key to aad
    }

    private fun normalizeJidForSecret(jid: String): String {
        val user = jid.substringBefore("@").substringBefore(":").substringBefore(".")
        val server = jid.substringAfter("@", "s.whatsapp.net")
        return "$user@$server"
    }

    private fun aesGcm(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    // WA text formatting regexes (from Go wa-text.go)
    private val waBoldRegex = Regex("(?<=[\\s>_~]|^)\\*(.+?)\\*(?=[^a-zA-Z\\d]|$)")
    private val waItalicRegex = Regex("(?<=[\\s>~*]|^)_(.+?)_(?=[^a-zA-Z\\d]|$)")
    private val waStrikethroughRegex = Regex("(?<=[\\s>_*]|^)~(.+?)~(?=[^a-zA-Z\\d]|$)")
    private val waInlineCodeRegex = Regex("(?<=[\\s>_*~]|^)`(.+?)`(?=[^a-zA-Z\\d]|$)")
    private val waOrderedListRegex = Regex("(?m)^(\\d{1,2})\\. ")
    private val waBulletedListRegex = Regex("(?m)^( *)\\* ")
    private val waBlockquoteRegex = Regex("(?m)^> ")
    private val waInlineURLRegex = Regex("\\[(.+?)]\\((.+?)\\)")

    fun convertWAFormattingToHtml(text: String): String {
        val sb = StringBuilder()
        var remaining = text
        while (true) {
            val start = remaining.indexOf("```")
            if (start == -1) break
            val end = remaining.indexOf("```", start + 3)
            if (end == -1) break
            val before = remaining.substring(0, start)
            val code = remaining.substring(start + 3, end)
            remaining = remaining.substring(end + 3)
            sb.append(formatInlineWA(before))
            if (code.contains('\n')) {
                sb.append("<pre><code>").append(escapeHtml(code)).append("</code></pre>")
            } else {
                sb.append("<code>").append(escapeHtml(code)).append("</code>")
            }
        }
        sb.append(formatInlineWA(remaining))
        return sb.toString()
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun formatInlineWA(text: String): String {
        var result = escapeHtml(text)

        // Blockquotes (Go parseWAFormattingToHTML blockquote handling)
        result = processBlockquotes(result)

        // Ordered lists (Go orderedListRegex)
        result = processOrderedLists(result)

        // Bulleted lists — must come after bold since * is used for both
        result = processBulletedLists(result)

        result = waBoldRegex.replace(result) { "<b>${it.groupValues[1]}</b>" }
        result = waItalicRegex.replace(result) { "<i>${it.groupValues[1]}</i>" }
        result = waStrikethroughRegex.replace(result) { "<s>${it.groupValues[1]}</s>" }
        result = waInlineCodeRegex.replace(result) { "<code>${it.groupValues[1]}</code>" }

        // Inline URLs (Go inlineURLRegex)
        result = waInlineURLRegex.replace(result) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }

        result = result.replace("\n", "<br>")
        return result
    }

    private fun processBlockquotes(text: String): String {
        val lines = text.split("\n")
        val result = StringBuilder()
        var inBlockquote = false
        for (line in lines) {
            if (line.startsWith("&gt; ")) {
                if (!inBlockquote) {
                    result.append("<blockquote>")
                    inBlockquote = true
                } else {
                    result.append("<br>")
                }
                result.append(line.removePrefix("&gt; "))
            } else {
                if (inBlockquote) {
                    result.append("</blockquote>")
                    inBlockquote = false
                }
                if (result.isNotEmpty()) result.append("\n")
                result.append(line)
            }
        }
        if (inBlockquote) result.append("</blockquote>")
        return result.toString()
    }

    private fun processOrderedLists(text: String): String {
        val lines = text.split("\n")
        val result = StringBuilder()
        var inList = false
        for (line in lines) {
            val match = Regex("^(\\d{1,2})\\. (.*)").find(line)
            if (match != null) {
                val listNumber = match.groupValues[1].toIntOrNull() ?: 1
                if (!inList) {
                    result.append("<ol start=\"$listNumber\">")
                    inList = true
                }
                result.append("<li value=\"$listNumber\">").append(match.groupValues[2]).append("</li>")
            } else {
                if (inList) {
                    result.append("</ol>")
                    inList = false
                }
                if (result.isNotEmpty()) result.append("\n")
                result.append(line)
            }
        }
        if (inList) result.append("</ol>")
        return result.toString()
    }

    private fun processBulletedLists(text: String): String {
        val lines = text.split("\n")
        val result = StringBuilder()
        var inList = false
        for (line in lines) {
            val match = Regex("^(?:\\* |- )(.*)").find(line)
            if (match != null) {
                if (!inList) {
                    result.append("<ul>")
                    inList = true
                }
                result.append("<li>").append(match.groupValues[1]).append("</li>")
            } else {
                if (inList) {
                    result.append("</ul>")
                    inList = false
                }
                if (result.isNotEmpty()) result.append("\n")
                result.append(line)
            }
        }
        if (inList) result.append("</ul>")
        return result.toString()
    }

    fun rerouteLIDSender(senderJid: String, participants: Map<String, String>?): String {
        if (!senderJid.contains("@lid")) return senderJid
        val phoneJid = participants?.get(senderJid)
        return phoneJid ?: senderJid
    }

    /**
     * Return the effective PollCreationMessage from a message regardless of version — modern
     * WhatsApp sends polls as pollCreationMessageV3 (field 64) or V2 (60), older ones as V1 (49).
     * Returns null if the message isn't a poll creation.
     */
    fun pollCreation(
        e2eMessage: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message?,
    ): com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.PollCreationMessage? = when {
        e2eMessage == null -> null
        e2eMessage.hasPollCreationMessageV3() -> e2eMessage.pollCreationMessageV3
        e2eMessage.hasPollCreationMessageV2() -> e2eMessage.pollCreationMessageV2
        e2eMessage.hasPollCreationMessage() -> e2eMessage.pollCreationMessage
        else -> null
    }

    fun getMessageType(e2eMessage: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message?): String {
        return when {
            e2eMessage == null -> "ignore"
            e2eMessage.hasConversation() || e2eMessage.hasExtendedTextMessage() -> "text"
            e2eMessage.hasImageMessage() -> "image ${e2eMessage.imageMessage.mimetype}"
            e2eMessage.hasStickerMessage() -> "sticker ${e2eMessage.stickerMessage.mimetype}"
            e2eMessage.hasVideoMessage() -> "video ${e2eMessage.videoMessage.mimetype}"
            e2eMessage.hasAudioMessage() -> "audio ${e2eMessage.audioMessage.mimetype}"
            e2eMessage.hasDocumentMessage() -> "document ${e2eMessage.documentMessage.mimetype}"
            e2eMessage.hasContactMessage() -> "contact"
            e2eMessage.hasLocationMessage() -> "location"
            pollCreation(e2eMessage) != null -> "poll"
            e2eMessage.hasReactionMessage() -> {
                if (e2eMessage.reactionMessage.text.isNullOrEmpty()) "reaction remove" else "reaction"
            }
            e2eMessage.hasEditedMessage() -> {
                val inner = e2eMessage.editedMessage?.message
                if (inner != null) getMessageType(inner) else "ignore"
            }
            e2eMessage.hasProtocolMessage() -> {
                when (e2eMessage.protocolMessage.type) {
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.REVOKE -> {
                        if (e2eMessage.protocolMessage.hasKey()) "revoke" else "ignore"
                    }
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.MESSAGE_EDIT -> "edit"
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.EPHEMERAL_SETTING -> "ephemeral setting"
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.HISTORY_SYNC_NOTIFICATION -> "history_sync"
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.APP_STATE_SYNC_KEY_SHARE -> "app_state_key"
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.INITIAL_SECURITY_NOTIFICATION_SETTING_SYNC,
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.APP_STATE_FATAL_EXCEPTION_NOTIFICATION,
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.SHARE_PHONE_NUMBER,
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_MESSAGE,
                    com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_RESPONSE_MESSAGE -> "ignore"
                    else -> "unknown_protocol_${e2eMessage.protocolMessage.type.number}"
                }
            }
            e2eMessage.hasSenderKeyDistributionMessage() -> "ignore"
            else -> "unknown"
        }
    }

    /**
     * Extract a human-readable body/preview from a decrypted [Message] (used for history-sync
     * backfill, where each WebMessageInfo wraps a Message). Mirrors the body logic in parseMessage.
     */
    fun extractMessageBody(m: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message): String {
        var e2e = m
        if (e2e.hasEditedMessage() && e2e.editedMessage.hasMessage()) e2e = e2e.editedMessage.message
        return when {
            e2e.hasConversation() -> e2e.conversation
            e2e.hasExtendedTextMessage() -> e2e.extendedTextMessage.text
            e2e.hasImageMessage() -> e2e.imageMessage.caption.ifEmpty { "[Image]" }
            e2e.hasVideoMessage() -> e2e.videoMessage.caption.ifEmpty { "[Video]" }
            e2e.hasAudioMessage() -> "[Audio]"
            e2e.hasDocumentMessage() -> "[Document: ${e2e.documentMessage.title}]"
            e2e.hasStickerMessage() -> "[Sticker]"
            e2e.hasContactMessage() -> "[Contact: ${e2e.contactMessage.displayName}]"
            e2e.hasLocationMessage() -> "[Location]"
            else -> ""
        }
    }

    /**
     * Parse an inbound <message> node...
     * Signal-decrypted (and unpadded) via the callback; otherwise the raw enc data is treated
     * as already-plaintext padded protobuf (legacy/no-crypto path).
     *
     * [decryptEnc] receives (senderJid, encType, encData) and returns the decrypted, still
     * padded plaintext, or null if decryption failed.
     */
    fun parseMessage(
        node: Node,
        decryptEnc: ((senderJid: String, encType: String, data: ByteArray) -> ByteArray?)? = null,
    ): WhatsAppMessage? {
        if (node.tag != "message") return null

        val rawFrom = node.attrs["from"] ?: return null
        // For 1:1 chats WhatsApp now addresses the sender by LID (e.g. 13184…@s.whatsapp.net) and
        // carries the phone number in sender_pn. Normalize to the PN so live messages map to the
        // same conversation as history (which is keyed by phone JID). Groups/broadcast keep `from`.
        val senderPn = node.attrs["sender_pn"]
        val from = if (!senderPn.isNullOrEmpty() && !rawFrom.contains("@g.us") && !rawFrom.contains("broadcast")) {
            senderPn.substringBefore(":").let { if (it.contains("@")) it else "$it@s.whatsapp.net" }
        } else {
            rawFrom
        }
        val id = node.attrs["id"] ?: return null
        val type = node.attrs["type"] ?: "text"
        val timestamp = node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000
        val participant = node.attrs["participant"]

        val encNode = node.getChildByTag("enc")
        if (encNode?.data != null) {
            return try {
                val encType = encNode.attrs["type"] ?: "msg"
                val senderJid = participant ?: from
                val decryptedPadded: ByteArray = if (decryptEnc != null) {
                    decryptEnc(senderJid, encType, encNode.data)
                        ?: return WhatsAppMessage(
                            id = id, from = from, to = node.attrs["to"] ?: "", body = "",
                            timestamp = timestamp, type = type, participant = participant,
                        )
                } else {
                    encNode.data
                }
                val plaintext = unpadMessage(decryptedPadded)
                var e2eMessage = com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message.parseFrom(plaintext)

                // Messages the user sends from another linked device are echoed to us
                // either wrapped in a DeviceSentMessage (carrying the real chat in
                // destinationJid) or, for 1:1, with `from` set to our own JID and the real
                // chat in the `recipient` attr. Unwrap/redirect so these map to the right
                // conversation instead of surfacing as a blank message from ourselves.
                // Ref whatsmeow message.go processProtocolParts / parseMessageSource.
                var chatJid = from
                var fromMe = false
                if (e2eMessage.hasDeviceSentMessage()) {
                    fromMe = true
                    val dsm = e2eMessage.deviceSentMessage
                    if (dsm.destinationJid.isNotEmpty()) chatJid = dsm.destinationJid
                    if (dsm.hasMessage()) e2eMessage = dsm.message
                } else {
                    val recipient = node.attrs["recipient"]
                    if (!recipient.isNullOrEmpty()) {
                        fromMe = true
                        chatJid = recipient
                    }
                }

                // Unwrap FutureProofMessage (Go events.go GetInnerMessage)
                if (e2eMessage.hasEditedMessage() && e2eMessage.editedMessage.hasMessage()) {
                    e2eMessage = e2eMessage.editedMessage.message
                }


                val parsedType = getMessageType(e2eMessage)
                // A message carrying only a SenderKeyDistributionMessage types as "ignore", but we
                // MUST still process it — it's what establishes the group sender key so subsequent
                // skmsg group messages can be decrypted. Let it through here; the caller processes
                // the key and then stops (see handleIncomingMessage). Other "ignore" / unknown
                // protocol messages are still dropped.
                val hasSenderKeyDistribution = e2eMessage.hasSenderKeyDistributionMessage()
                if ((parsedType == "ignore" && !hasSenderKeyDistribution) ||
                    parsedType.startsWith("unknown_protocol_")
                ) {
                    return null
                }

                val body = when {
                    e2eMessage.hasConversation() -> e2eMessage.conversation
                    e2eMessage.hasExtendedTextMessage() -> e2eMessage.extendedTextMessage.text
                    e2eMessage.hasImageMessage() -> e2eMessage.imageMessage.caption.ifEmpty { "[Image]" }
                    e2eMessage.hasVideoMessage() -> e2eMessage.videoMessage.caption.ifEmpty { "[Video]" }
                    e2eMessage.hasAudioMessage() -> "[Audio]"
                    e2eMessage.hasDocumentMessage() -> "[Document: ${e2eMessage.documentMessage.title}]"
                    e2eMessage.hasStickerMessage() -> "[Sticker]"
                    e2eMessage.hasContactMessage() -> {
                        val c = e2eMessage.contactMessage
                        "[Contact: ${c.displayName}]\n${c.vcard}"
                    }
                    e2eMessage.hasLocationMessage() -> {
                        val loc = e2eMessage.locationMessage
                        val lat = loc.degreesLatitude
                        val lng = loc.degreesLongitude
                        val name = loc.name.ifEmpty {
                            val latDir = if (lat >= 0) 'N' else 'S'
                            val lngDir = if (lng >= 0) 'E' else 'W'
                            "%.4f° %c %.4f° %c".format(Math.abs(lat), latDir, Math.abs(lng), lngDir)
                        }
                        val mapsUrl = "https://maps.google.com/?q=%.5f,%.5f".format(lat, lng)
                        "Location: $name\n${loc.address}\n$mapsUrl"
                    }
                    e2eMessage.hasReactionMessage() -> e2eMessage.reactionMessage.text
                    pollCreation(e2eMessage) != null -> {
                        val poll = pollCreation(e2eMessage)!!
                        buildString {
                            append("📊 ")
                            append(poll.name)
                            poll.optionsList.forEach { append("\n• "); append(it.optionName) }
                        }
                    }
                    e2eMessage.hasProtocolMessage() -> {
                        when (parsedType) {
                            "revoke" -> "[Message Deleted]"
                            "edit" -> e2eMessage.protocolMessage.editedMessage?.conversation ?: "[Edited]"
                            "ephemeral setting" -> {
                                val timer = e2eMessage.protocolMessage.ephemeralExpiration
                                if (timer == 0) "[Disappearing Messages Disabled]"
                                else "[Disappearing Messages: ${formatDisappearingTimer(timer)}]"
                            }
                            else -> ""
                        }
                    }
                    else -> ""
                }
                val mediaUrl = when {
                    e2eMessage.hasImageMessage() -> e2eMessage.imageMessage.url
                    e2eMessage.hasVideoMessage() -> e2eMessage.videoMessage.url
                    e2eMessage.hasAudioMessage() -> e2eMessage.audioMessage.url
                    e2eMessage.hasDocumentMessage() -> e2eMessage.documentMessage.url
                    e2eMessage.hasStickerMessage() -> e2eMessage.stickerMessage.url
                    else -> null
                }
                val isRevoke = parsedType == "revoke"
                val revokeTargetId = if (isRevoke) e2eMessage.protocolMessage.key.id else null
                val isEdit = parsedType == "edit"
                val editTargetId = if (isEdit) e2eMessage.protocolMessage.key.id else null

                val locationData = when {
                    e2eMessage.hasLocationMessage() -> LocationData(
                        latitude = e2eMessage.locationMessage.degreesLatitude,
                        longitude = e2eMessage.locationMessage.degreesLongitude,
                        name = e2eMessage.locationMessage.name.ifEmpty { null },
                        address = e2eMessage.locationMessage.address.ifEmpty { null },
                        url = e2eMessage.locationMessage.url.ifEmpty { null },
                    )
                    else -> null
                }

                val contactData = when {
                    e2eMessage.hasContactMessage() -> ContactData(
                        displayName = e2eMessage.contactMessage.displayName,
                        vcard = e2eMessage.contactMessage.vcard,
                    )
                    else -> null
                }

                val pollData: PollData? = pollCreation(e2eMessage)?.let { poll ->
                    PollData(
                        question = poll.name,
                        options = poll.optionsList.map { it.optionName },
                        selectableOptionCount = poll.selectableOptionsCount,
                    )
                }

                val groupInviteData: GroupInviteMeta? = null

                val disappearingTimer = if (parsedType == "ephemeral setting") {
                    e2eMessage.protocolMessage.ephemeralExpiration.toLong()
                } else null

                val contextInfo = extractContextInfo(e2eMessage)

                // View-once detection (Go convertMediaMessage viewOnce check)
                val isViewOnce = false

                val isHD = false

                WhatsAppMessage(
                    id = id,
                    from = chatJid,
                    to = node.attrs["to"] ?: "",
                    body = body,
                    timestamp = timestamp,
                    type = type,
                    participant = participant,
                    mediaUrl = mediaUrl,
                    isReaction = e2eMessage.hasReactionMessage(),
                    reactionTargetId = if (e2eMessage.hasReactionMessage()) e2eMessage.reactionMessage.key.id else null,
                    isRevoke = isRevoke,
                    revokeTargetId = revokeTargetId,
                    isEdit = isEdit,
                    editTargetId = editTargetId,
                    messageType = parsedType,
                    locationData = locationData,
                    contactData = contactData,
                    pollData = pollData,
                    groupInviteData = groupInviteData,
                    disappearingTimer = disappearingTimer,
                    isForwarded = contextInfo.isForwarded,
                    forwardingScore = contextInfo.forwardingScore,
                    replyToId = contextInfo.replyToId,
                    mentionedJids = contextInfo.mentionedJids,
                    isViewOnce = isViewOnce,
                    isHD = isHD,
                    isFromMe = fromMe,
                    e2eMessage = e2eMessage,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse E2E message", e)
                WhatsAppMessage(id = id, from = from, to = node.attrs["to"] ?: "", body = "", timestamp = timestamp, type = type, participant = participant)
            }
        }

        // Fallback: plaintext body node
        val bodyNode = node.getChildByTag("body")
        val body = bodyNode?.data?.let { String(it, Charsets.UTF_8) } ?: ""

        return WhatsAppMessage(
            id = id,
            from = from,
            to = node.attrs["to"] ?: "",
            body = body,
            timestamp = timestamp,
            type = type,
            participant = participant,
        )
    }
}

data class WhatsAppMessage(
    val id: String,
    val from: String,
    val to: String,
    val body: String,
    val timestamp: Long,
    val type: String,
    val participant: String? = null,
    val mediaUrl: String? = null,
    val isReaction: Boolean = false,
    val reactionTargetId: String? = null,
    val isRevoke: Boolean = false,
    val revokeTargetId: String? = null,
    val isEdit: Boolean = false,
    val editTargetId: String? = null,
    val messageType: String = "unknown",
    val locationData: LocationData? = null,
    val contactData: ContactData? = null,
    val pollData: PollData? = null,
    val groupInviteData: GroupInviteMeta? = null,
    val disappearingTimer: Long? = null,
    val isForwarded: Boolean = false,
    val forwardingScore: Int = 0,
    val replyToId: String? = null,
    val mentionedJids: List<String> = emptyList(),
    val isViewOnce: Boolean = false,
    val isHD: Boolean = false,
    /** True when this message was sent by the local user from another linked
     *  device (DeviceSentMessage / own-JID echo). Lets the client render it as
     *  outgoing instead of an incoming (or blank) bubble. */
    val isFromMe: Boolean = false,
    val e2eMessage: com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto.Message? = null,
)

data class LocationData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val name: String? = null,
    val address: String? = null,
    val url: String? = null,
    val isLive: Boolean = false,
) {
    fun toGeoUri(): String = "geo:%.5f,%.5f".format(latitude, longitude)
    fun toMapsUrl(): String = "https://maps.google.com/?q=%.5f,%.5f".format(latitude, longitude)
}

data class ContactData(
    val displayName: String = "",
    val vcard: String = "",
)

data class PollData(
    val question: String = "",
    val options: List<String> = emptyList(),
    val selectableOptionCount: Int = 0,
    val isPollVote: Boolean = false,
    val pollCreationMessageKey: String? = null,
    val encPayload: List<ByteArray>? = null,
) {
    companion object
}

data class ContextInfoResult(
    val isForwarded: Boolean = false,
    val forwardingScore: Int = 0,
    val replyToId: String? = null,
    val mentionedJids: List<String> = emptyList(),
)
