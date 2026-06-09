package com.vayunmathur.messages.whatsapp

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.digests.SHA256Digest

/**
 * WhatsApp Web protocol implementation.
 * Handles Noise protocol handshake, message encoding/decoding, and binary protocol.
 * 
 * Implements Noise_XX_25519_AESGCM_SHA256 handshake as per:
 * https://github.com/tulir/whatsmeow/blob/main/handshake.go
 */
object WhatsAppProtocol {
    private const val TAG = "WhatsAppProtocol"

    // WhatsApp Web WebSocket URL
    const val WS_URL = "wss://web.whatsapp.com/ws/chat"

    // Protocol constants
    private const val NOISE_PROTOCOL = "Noise_XX_25519_AESGCM_SHA256\u0000\u0000\u0000\u0000"
    private const val WA_HEADER = "WA"
    private const val WA_VERSION = "2.3000.1017131629"
    
    // WhatsApp certificate authority public key (Ed25519)
    // From whatsmeow/handshake.go line 27
    private val WA_CERT_PUBKEY = byteArrayOf(
        0x14, 0x23, 0x75, 0x57, 0x4d, 0x0a, 0x58, 0x71, 0x66, 0xaa.toByte(), 0xe7.toByte(), 0x1e, 0xbe.toByte(), 0x51, 0x64, 0x37,
        0xc4.toByte(), 0xa2.toByte(), 0x8b.toByte(), 0x73, 0xe3.toByte(), 0x69, 0x5c, 0x6c, 0xe1.toByte(), 0xf7.toByte(), 0xf9.toByte(), 0x54, 0x5d, 0xa8.toByte(), 0xee.toByte(), 0x6b
    )

    /**
     * Noise protocol handshake message.
     */
    data class HandshakeMessage(
        val clientHello: ClientHello,
    )

    data class ClientHello(
        val ephemeral: String, // Base64 encoded public key
        val static: String?, // Base64 encoded static key (optional)
        val payload: String, // Base64 encoded encrypted payload
    )

    /**
     * WhatsApp message node (binary XML-like structure).
     */
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

        /**
         * Initialize handshake with protocol pattern and header
         */
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

        /**
         * Mix data into the handshake hash (transcript)
         */
        fun authenticate(data: ByteArray) {
            hash = sha256(hash + data)
        }

        /**
         * Encrypt plaintext with AES-GCM using current key
         * Increments counter and mixes ciphertext into transcript
         */
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

        /**
         * Decrypt ciphertext with AES-GCM using current key
         * Increments counter and mixes ciphertext into transcript on success
         */
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

        /**
         * Mix shared secret (from X25519) into the key
         * Uses HKDF-SHA256 to derive new salt and key
         */
        fun mixSharedSecretIntoKey(privateKey: ByteArray, publicKey: ByteArray) {
            val sharedSecret = x25519(privateKey, publicKey)
            mixIntoKey(sharedSecret)
        }

        /**
         * Mix arbitrary data into the key using HKDF
         */
        fun mixIntoKey(data: ByteArray) {
            counter = 0u
            val (newSalt, newKey) = extractAndExpand(salt, data)
            salt = newSalt
            key = SecretKeySpec(newKey, "AES")
        }

        /**
         * HKDF-SHA256 extract and expand
         * Returns (salt, key) pair, each 32 bytes
         */
        private fun extractAndExpand(salt: ByteArray, data: ByteArray): Pair<ByteArray, ByteArray> {
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(data, salt, null))
            
            val writeKey = ByteArray(32)
            val readKey = ByteArray(32)
            hkdf.generateBytes(writeKey, 0, 32)
            hkdf.generateBytes(readKey, 0, 32)
            
            return Pair(writeKey, readKey)
        }

        /**
         * Finish handshake and derive final read/write keys
         */
        fun finish(): Pair<SecretKeySpec, SecretKeySpec> {
            val (writeKey, readKey) = extractAndExpand(salt, ByteArray(0))
            return Pair(
                SecretKeySpec(writeKey, "AES"),
                SecretKeySpec(readKey, "AES")
            )
        }

        private fun generateIV(counter: UInt): ByteArray {
            val iv = ByteArray(12)
            // First 4 bytes are 0, last 8 bytes are counter (big-endian)
            ByteBuffer.wrap(iv, 4, 8)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(counter.toLong())
            return iv
        }
    }

    /**
     * Perform X25519 scalar multiplication
     */
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val privParams = X25519PrivateKeyParameters(privateKey, 0)
        val pubParams = X25519PublicKeyParameters(publicKey, 0)
        val agreement = X25519Agreement()
        agreement.init(privParams)
        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(pubParams, sharedSecret, 0)
        return sharedSecret
    }

    /**
     * Generate X25519 key pair
     */
    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val random = SecureRandom()
        val privateKey = ByteArray(32)
        random.nextBytes(privateKey)
        // Clamp the private key as per X25519 spec
        privateKey[0] = (privateKey[0].toInt() and 248).toByte()
        privateKey[31] = (privateKey[31].toInt() and 127).toByte()
        privateKey[31] = (privateKey[31].toInt() or 64).toByte()
        
        val privParams = X25519PrivateKeyParameters(privateKey, 0)
        val publicKey = privParams.generatePublicKey().encoded
        return Pair(privateKey, publicKey)
    }

    /**
     * Encrypt data using AES-256-GCM.
     */
    fun encryptAesGcm(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypt data using AES-256-GCM.
     */
    fun decryptAesGcm(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Compute HMAC-SHA256.
     */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key, "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    /**
     * Compute SHA256 hash.
     */
    fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    /**
     * WhatsApp binary XML token constants.
     * From whatsmeow/binary/token/token.go
     */
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
    }

    /**
     * Binary XML encoder — encodes Nodes to WhatsApp binary wire format.
     * From whatsmeow/binary/encoder.go
     */
    private class BinaryEncoder {
        private val data = mutableListOf<Byte>(0) // starts with 0 flag byte

        fun getData(): ByteArray = data.toByteArray()

        private fun pushByte(b: Byte) { data.add(b) }
        private fun pushByte(b: Int) { data.add(b.toByte()) }
        private fun pushBytes(bytes: ByteArray) { bytes.forEach { data.add(it) } }

        private fun pushInt8(value: Int) {
            pushByte((value and 0xFF).toByte())
        }

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
            } else if (validateNibble(value)) {
                writePackedBytes(value, BinaryToken.NIBBLE_8)
            } else if (validateHex(value)) {
                writePackedBytes(value, BinaryToken.HEX_8)
            } else {
                writeStringRaw(value)
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

        private fun writeAttributes(attrs: Map<String, String>) {
            for ((key, value) in attrs) {
                if (value.isEmpty()) continue
                writeString(key)
                writeString(value)
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
            '\u0000' -> 15
            else -> throw IllegalArgumentException("Invalid hex char: $c")
        }
    }

    /**
     * Binary XML decoder — decodes WhatsApp binary wire format to Nodes.
     * From whatsmeow/binary/decoder.go
     */
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
            if ((startByte shr 7) != 0) {
                result = result.dropLast(1)
            }
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
                    // Double byte token — skip for now, return placeholder
                    val idx = readInt8()
                    "dict_${tag - BinaryToken.DICTIONARY_0}_$idx"
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

    /**
     * Encode node to WhatsApp binary XML format.
     */
    fun encodeNode(node: Node): ByteArray {
        val encoder = BinaryEncoder()
        encoder.writeNode(node)
        return encoder.getData()
    }

    /**
     * Decode binary data to node.
     */
    fun decodeNode(data: ByteArray): Node {
        val decoder = BinaryDecoder(data)
        return decoder.readNode()
    }

    /**
     * Build a message node for sending text.
     */
    fun buildTextMessage(to: String, id: String, text: String): Node {
        return Node(
            tag = "message",
            attrs = mapOf(
                "to" to to,
                "id" to id,
                "type" to "chat"
            ),
            content = listOf(
                Node(
                    tag = "body",
                    data = text.toByteArray(Charsets.UTF_8)
                )
            )
        )
    }

    /**
     * Parse incoming message node.
     */
    fun parseMessage(node: Node): WhatsAppMessage? {
        if (node.tag != "message") return null

        val from = node.attrs["from"] ?: return null
        val id = node.attrs["id"] ?: return null
        val type = node.attrs["type"] ?: "chat"
        val timestamp = node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000

        val bodyNode = node.content.find { it.tag == "body" }
        val body = bodyNode?.data?.let { String(it, Charsets.UTF_8) } ?: ""

        return WhatsAppMessage(
            id = id,
            from = from,
            to = node.attrs["to"] ?: "",
            body = body,
            timestamp = timestamp,
            type = type
        )
    }
}

/**
 * Parsed WhatsApp message.
 */
data class WhatsAppMessage(
    val id: String,
    val from: String,
    val to: String,
    val body: String,
    val timestamp: Long,
    val type: String,
)
