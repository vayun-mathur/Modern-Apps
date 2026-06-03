package com.vayunmathur.messages.meta

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Meta (Facebook/Messenger/Instagram) Lightspeed protocol implementation.
 * Handles MQTT messages, task encoding, and protocol specifics.
 */
object MetaProtocol {
    private const val TAG = "MetaProtocol"

    // MQTT topics
    const val TOPIC_LS_REQ = "/ls_req"
    const val TOPIC_LS_RESP = "/ls_resp"
    const val TOPIC_LS_APP_SETTINGS = "/ls_app_settings"
    const val TOPIC_TMS = "/t_ms"
    const val TOPIC_TPI = "/t_p"

    // Messenger endpoints
    const val MESSENGER_BASE_URL = "https://www.messenger.com"
    const val MESSENGER_MQTT_URL = "wss://edge-chat.messenger.com/chat"

    // Instagram endpoints
    const val INSTAGRAM_BASE_URL = "https://www.instagram.com"
    const val INSTAGRAM_MQTT_URL = "wss://edge-chat.instagram.com/chat"

    /**
     * Lightspeed task for sending a message.
     */
    @Serializable
    data class SendMessageTask(
        val threadId: String,
        val messageId: String,
        val text: String,
        val timestamp: Long,
    )

    /**
     * Lightspeed task for sending a reaction.
     */
    @Serializable
    data class SendReactionTask(
        val threadId: String,
        val messageId: String,
        val reaction: String,
    )

    /**
     * Lightspeed task for marking thread as read.
     */
    @Serializable
    data class ThreadMarkReadTask(
        val threadId: String,
        val lastReadWatermark: Long,
    )

    /**
     * Parsed MQTT message.
     */
    data class MqttMessage(
        val topic: String,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as MqttMessage
            if (topic != other.topic) return false
            if (!payload.contentEquals(other.payload)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = topic.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Parsed message from Meta platform.
     */
    data class MetaMessage(
        val messageId: String,
        val threadId: String,
        val senderId: String,
        val senderName: String?,
        val text: String,
        val timestamp: Long,
        val isGroup: Boolean,
    )

    /**
     * Parse incoming MQTT payload to MetaMessage.
     * Simplified implementation - real protocol uses binary format with tables/steps.
     */
    fun parseMessage(payload: ByteArray, platform: MetaAuthData.Platform): MetaMessage? {
        return try {
            val json = String(payload, Charsets.UTF_8)
            val obj = Json.parseToJsonElement(json).jsonObject

            // Extract fields based on platform
            val messageId = obj["message_id"]?.jsonPrimitive?.content ?: return null
            val threadId = obj["thread_id"]?.jsonPrimitive?.content ?: return null
            val senderId = obj["sender_id"]?.jsonPrimitive?.content ?: return null
            val text = obj["text"]?.jsonPrimitive?.content ?: ""
            val timestamp = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: System.currentTimeMillis()

            MetaMessage(
                messageId = messageId,
                threadId = threadId,
                senderId = senderId,
                senderName = obj["sender_name"]?.jsonPrimitive?.content,
                text = text,
                timestamp = timestamp,
                isGroup = obj["is_group"]?.jsonPrimitive?.content?.toBoolean() ?: false
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build MQTT payload for sending a message.
     */
    fun buildSendMessagePayload(task: SendMessageTask): ByteArray {
        val json = JsonObject(
            mapOf(
                "type" to JsonObject(mapOf("value" to kotlinx.serialization.json.JsonPrimitive("send_message"))),
                "thread_id" to JsonObject(mapOf("value" to kotlinx.serialization.json.JsonPrimitive(task.threadId))),
                "message_id" to JsonObject(mapOf("value" to kotlinx.serialization.json.JsonPrimitive(task.messageId))),
                "text" to JsonObject(mapOf("value" to kotlinx.serialization.json.JsonPrimitive(task.text))),
                "timestamp" to JsonObject(mapOf("value" to kotlinx.serialization.json.JsonPrimitive(task.timestamp.toString()))),
            )
        )
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Extract timestamp from message ID (Meta IDs contain Unix ms).
     */
    fun extractTimestampFromId(messageId: String): Long {
        // Meta message IDs are typically numeric strings containing timestamp
        return try {
            messageId.toLong()
        } catch (e: NumberFormatException) {
            System.currentTimeMillis()
        }
    }
}
