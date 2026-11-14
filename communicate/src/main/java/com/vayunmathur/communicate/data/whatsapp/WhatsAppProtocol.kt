package com.vayunmathur.communicate.data.whatsapp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto

/**
 * WhatsApp Web protocol implementation.
 * Implements Noise_XX_25519_AESGCM_SHA256 handshake, binary XML encoding,
 * protobuf E2E message format, and media encryption.
 *
 * Reference: whatsmeow (github.com/tulir/whatsmeow)
 */
object WhatsAppProtocol {
    internal const val TAG = "WhatsAppProtocol"

    // NOTE: Companion-web endpoints (WS_URL/WS_ORIGIN → web.whatsapp.com) were removed here.
    // The primary client connects over a raw Noise socket to g.whatsapp.net — see
    // data/whatsapp/transport/WhatsAppSocket.kt. Do not reintroduce the WebSocket URL.

    // Noise protocol pattern — 32 bytes, null-padded
    const val NOISE_START_PATTERN = "Noise_XX_25519_AESGCM_SHA256\u0000\u0000\u0000\u0000"

    // WA connection header: 'W', 'A', WAMagicValue(6), DictVersion(3)
    val WA_CONN_HEADER = byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 6, 3)

    // Frame constants (from whatsmeow/socket/constants.go)
    const val FRAME_MAX_SIZE = 1 shl 24
    const val FRAME_LENGTH_SIZE = 3

    // WhatsApp web message ID prefix
    internal const val WEB_MESSAGE_ID_PREFIX = "3EB0"

    // WhatsApp Android app version — PINNED to the extracted APK
    // (apk-analysis/whatsapp: versionName 2.26.29.73, versionCode 262907320).
    // The registration `token` attestation binds this exact version + classes.dex MD5 +
    // signing cert, so WA_VERSION, the pinned signature, and the dex MD5 must all come from
    // the SAME APK build. Used by the primary ClientPayload (PrimaryClientPayload.kt).
    val WA_VERSION = intArrayOf(2, 26, 29, 73)
    const val WA_VERSION_NAME = "2.26.29.73"
    const val WA_VERSION_CODE = 262907320

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

    /** One node of the WhatsApp binary XML (binary stanza) tree: tag, attributes and children. */
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
        /** Noise handshake constants (hash and AEAD sizes) for [WhatsAppProtocol.NoiseHandshake]. */
        companion object {
            private const val HASH_SIZE = 32
            private const val EXPANDED_SIZE = 64
            private const val GCM_IV_OFFSET = 8
            private const val GCM_IV_LENGTH = 4
        }
        private var hash = ByteArray(32)
        private var salt = ByteArray(32)
        private var key: SecretKeySpec? = null
        private var counter: UInt = 0u

        fun start(pattern: String, header: ByteArray) {
            val data = pattern.toByteArray(Charsets.UTF_8)
            hash = if (data.size == HASH_SIZE) {
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
            val out = hkdfSha256(data, salt, ByteArray(0), EXPANDED_SIZE)
            return Pair(out.copyOfRange(0, HASH_SIZE), out.copyOfRange(HASH_SIZE, EXPANDED_SIZE))
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

            ByteBuffer.wrap(iv, GCM_IV_OFFSET, GCM_IV_LENGTH)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(counter.toInt())
            return iv
        }
    }

// -- Cryptography primitives (NoiseHandshake + derived helpers depend on these; higher-level
    // consumers live in WhatsAppProtocol* extension files). --

    // X25519 via Rust (constant-time x25519-dalek through libwhatsapp_signal.so).
    // Falls back to throwing if Rust lib unavailable — pairing/connect requires keys.
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        return RustWhatsAppCrypto.x25519Agreement(privateKey, publicKey)
            ?: throw IllegalStateException("RustWhatsAppCrypto.x25519Agreement returned null")
    }

    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val kp = RustWhatsAppCrypto.generateKeyPairSplit()
        return Pair(kp.privateKey, kp.publicKey)
    }

    /** HKDF-SHA256 (RFC 5869) on the platform Mac. null/empty salt = zero salt. */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(actualSalt, "HmacSHA256")) }.doFinal(ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
            mac.update(t); mac.update(info); mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; counter++
        }
        return out
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

    /** The key material split out of a media key: IV, cipher key and MAC key. */
    data class MediaKeys(
        val iv: ByteArray,
        val cipherKey: ByteArray,
        val macKey: ByteArray,
        val refKey: ByteArray,
    )

    /** The result of encrypting media for upload: media key, ciphertext and hashes. */
    data class MediaEncryptResult(
        val mediaKey: ByteArray,
        val encryptedData: ByteArray,
        val fileSha256: ByteArray,
        val fileEncSha256: ByteArray,
        val fileLength: Long,
    )

    // -- Binary XML encoding/decoding (codec lives in WhatsAppProtocolBinary.kt; token tables stay here) --

    internal object BinaryToken {
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

        val doubleByteTokens: Array<Array<String>> = BinaryTokenTables.doubleByteTokens

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

        val singleByteTokens: Array<String> = BinaryTokenSingle.singleByteTokens
        private val singleByteIndex: Map<String, Byte> by lazy {
            val map = HashMap<String, Byte>(singleByteTokens.size)
            for (i in singleByteTokens.indices) {
                if (singleByteTokens[i].isNotEmpty()) {
                    map[singleByteTokens[i]] = i.toByte()
                }
            }
            map
        }

        private const val BYTE_MASK = 0xFF

        fun indexOfSingleToken(token: String): Int {
            return singleByteIndex[token]?.toInt()?.and(BYTE_MASK) ?: -1
        }

        fun indexOfDoubleByteToken(token: String): Triple<Int, Int, Boolean> {
            val pair = doubleByteIndex[token] ?: return Triple(0, 0, false)
            return Triple(pair.first.toInt() and BYTE_MASK, pair.second.toInt() and BYTE_MASK, true)
        }

        fun getDoubleToken(dictIndex: Int, tokenIndex: Int): String {
            if (dictIndex < 0 || dictIndex >= doubleByteTokens.size) return ""
            if (tokenIndex < 0 || tokenIndex >= doubleByteTokens[dictIndex].size) return ""
            return doubleByteTokens[dictIndex][tokenIndex]
        }
    }

    // Binary codec (BinaryEncoder/BinaryDecoder) lives in WhatsAppProtocolBinary.kt.

    // (encodeNode/decodeNode, cert verification, frame unpacking and frame helpers
    // live in WhatsAppProtocolBinary.kt.)

    // -- Message node builders live in WhatsAppProtocolBuilders.kt /
    // WhatsAppProtocolBuilders2.kt (same package, extension functions). --

    /** One fan-out copy of a group message, encrypted for a single device. */
    data class ParticipantEnc(val deviceJid: String, val encType: String, val ciphertext: ByteArray)

    // (buildFanOutMessageNode, retry receipts, queries, reactions — see Builders files.)

    // (receipt/media builders — see WhatsAppProtocolBuilders.kt.)

    // -- Rich send content (mentions / quoted reply / link preview) --------------------------------

    /** A quoted-reply context: the stanza id of the message being replied to, the sender of that
     *  message (participant), and (best-effort) the quoted message's proto for the preview. */
    data class QuotedContext(
        val stanzaId: String,
        val participant: String,
        val quotedMessage: com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message? = null,
    )

    /** A URL link-preview attached to an ExtendedTextMessage. */
    data class LinkPreview(
        val matchedText: String,
        val canonicalUrl: String,
        val title: String? = null,
        val description: String? = null,
        val jpegThumbnail: ByteArray? = null,
    )

    // (buildContextInfo/buildTextProto — see WhatsAppProtocolBuilders2.kt.)

    // (buildEditProto/buildRevokeProto/presence/keepalive/ack — see
    // WhatsAppProtocolBuilders2.kt; frame helpers — see WhatsAppProtocolBinary.kt.)

    // -- Message parsing (see WhatsAppProtocolParsing.kt) --
    // (formatDisappearingTimer/extractContextInfo live there as private extensions.)
    // (Poll/location/contact/disappearing/group builders — see WhatsAppProtocolBuilders2.kt;
    // poll-vote crypto — see WhatsAppProtocolCrypto.kt.)

    // (WA formatting, type/body helpers, parseMessage — see WhatsAppProtocolParsing.kt.)

}

/** A parsed incoming WhatsApp message stanza, flattened for the repository. */
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
    val e2eMessage: com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message? = null,
)

/** A shared location: coordinates plus optional place name/address. */
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

/** A shared contact card: display name and vCard payload. */
data class ContactData(
    val displayName: String = "",
    val vcard: String = "",
)

/** A poll: question, options and how many options may be picked. */
data class PollData(
    val question: String = "",
    val options: List<String> = emptyList(),
    val selectableOptionCount: Int = 0,
    val isPollVote: Boolean = false,
    val pollCreationMessageKey: String? = null,
    val encPayload: List<ByteArray>? = null,
) {
    /** Factories for WhatsApp protocol values. */
    companion object
}

/** The context info extracted from a message: forwarding, reply and mention metadata. */
data class ContextInfoResult(
    val isForwarded: Boolean = false,
    val forwardingScore: Int = 0,
    val replyToId: String? = null,
    val mentionedJids: List<String> = emptyList(),
)
