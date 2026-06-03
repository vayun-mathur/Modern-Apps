package com.vayunmathur.messages.whatsapp

import android.util.Base64
import com.vayunmathur.messages.util.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json as KotlinJson
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WhatsApp Web protocol implementation.
 * Handles Noise protocol handshake, message encoding/decoding, and binary protocol.
 */
object WhatsAppProtocol {
    private const val TAG = "WhatsAppProtocol"

    // WhatsApp Web WebSocket URL
    const val WS_URL = "wss://web.whatsapp.com/ws/chat"

    // Protocol constants
    private const val NOISE_PROTOCOL = "Noise_XX_25519_AESGCM_SHA256\0\0\0\0"
    private const val WA_HEADER = "WA"
    private const val WA_VERSION = "2.3000.1017131629"

    /**
     * Noise protocol handshake message.
     */
    @Serializable
    data class HandshakeMessage(
        val clientHello: ClientHello,
    )

    @Serializable
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
     * Encrypt data using AES-256-GCM.
     */
    fun encryptAesGcm(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypt data using AES-256-GCM.
     */
    fun decryptAesGcm(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
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
     * Encode node to binary format.
     * Simplified implementation - full binary protocol is complex.
     */
    fun encodeNode(node: Node): ByteArray {
        // Simplified JSON-based encoding for initial implementation
        // Full binary protocol requires implementing WhatsApp's binary XML format
        val json = KotlinJson.encodeToString(node.toMap())
        return json.toByteArray(Charsets.UTF_8)
    }

    /**
     * Decode binary data to node.
     */
    fun decodeNode(data: ByteArray): Node {
        val json = String(data, Charsets.UTF_8)
        val map = KotlinJson.decodeFromString<Map<String, Any>>(json)
        return map.toNode()
    }

    private fun Node.toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["tag"] = tag
        if (attrs.isNotEmpty()) map["attrs"] = attrs
        if (content.isNotEmpty()) map["content"] = content.map { it.toMap() }
        data?.let { map["data"] = Base64.encodeToString(it, Base64.NO_WRAP) }
        return map
    }

    private fun Map<String, Any>.toNode(): Node {
        val tag = this["tag"] as String
        val attrs = (this["attrs"] as? Map<String, String>) ?: emptyMap()
        val content = (this["content"] as? List<Map<String, Any>>)?.map { it.toNode() } ?: emptyList()
        val data = (this["data"] as? String)?.let { Base64.decode(it, Base64.NO_WRAP) }
        return Node(tag, attrs, content, data)
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
