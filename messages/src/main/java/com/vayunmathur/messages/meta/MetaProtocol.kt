package com.vayunmathur.messages.meta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

object MetaProtocol {
    private const val TAG = "MetaProtocol"

    // MQTT Topics (from messagix/topics.go)
    const val TOPIC_LS_APP_SETTINGS = "/ls_app_settings"
    const val TOPIC_LS_FOREGROUND_STATE = "/ls_foreground_state"
    const val TOPIC_LS_REQ = "/ls_req"
    const val TOPIC_LS_RESP = "/ls_resp"
    const val TOPIC_T_MS = "/t_ms"
    const val TOPIC_THREAD_TYPING = "/thread_typing"
    const val TOPIC_ORCA_TYPING_NOTIFICATIONS = "/orca_typing_notifications"
    const val TOPIC_ORCA_PRESENCE = "/orca_presence"
    const val TOPIC_LEGACY_WEB = "/legacy_web"
    const val TOPIC_WEBRTC = "/webrtc"
    const val TOPIC_BR_SR = "/br_sr"
    const val TOPIC_SR_RES = "/sr_res"
    const val TOPIC_GRAPHQL = "/graphql"

    // Messenger endpoints
    const val MESSENGER_BASE_URL = "https://www.messenger.com"
    const val MESSENGER_MQTT_URL = "wss://edge-chat.messenger.com/chat?"

    // Instagram endpoints
    const val INSTAGRAM_BASE_URL = "https://www.instagram.com"
    const val INSTAGRAM_MQTT_URL = "wss://edge-chat.instagram.com/chat?"

    // Connection codes (from messagix/codes.go)
    const val CONNECTION_ACCEPTED = 0
    const val CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION = 1
    const val CONNECTION_REFUSED_IDENTIFIER_REJECTED = 2
    const val CONNECTION_REFUSED_SERVER_UNAVAILABLE = 3
    const val CONNECTION_REFUSED_BAD_USERNAME_OR_PASSWORD = 4
    const val CONNECTION_REFUSED_UNAUTHORIZED = 5
    const val CONNECTION_REFUSED_UNKNOWN_24 = 24

    // LS Request types (from messagix/socket.go)
    const val LS_REQUEST_TYPE_DB_QUERY = 1
    const val LS_REQUEST_TYPE_DB_QUERY_CURSOR = 2
    const val LS_REQUEST_TYPE_TASK = 3
    const val LS_REQUEST_TYPE_STATELESS = 4

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val nextTaskId = AtomicLong(-1)

    fun getTaskId(): Long = nextTaskId.incrementAndGet()

    // --- Connect JSON (from messagix/json.go) ---

    @Serializable
    data class ConnectJson(
        @SerialName("u") val accountId: String,
        @SerialName("s") val sessionId: Long,
        @SerialName("cp") val clientCapabilities: Int = 3,
        @SerialName("ecp") val capabilities: Int = 10,
        @SerialName("chat_on") val chatOn: Boolean = true,
        @SerialName("fg") val fg: Boolean = false,
        @SerialName("d") val cid: String,
        @SerialName("ct") val connectionType: String,
        @SerialName("mqtt_sid") val mqttSid: String = "",
        @SerialName("aid") val appId: Long,
        @SerialName("st") val subscribedTopics: List<String> = emptyList(),
        @SerialName("pm") val postMessage: List<ConnectPostMessage> = emptyList(),
        @SerialName("dc") val dc: String = "",
        @SerialName("no_auto_fg") val noAutoFg: Boolean = true,
        @SerialName("gas") val gas: JsonElement = JsonNull,
        @SerialName("pack") val pack: List<JsonElement> = emptyList(),
        @SerialName("php_override") val hostNameOverride: String = "",
        @SerialName("p") val p: JsonElement = JsonNull,
        @SerialName("a") val userAgent: String,
        @SerialName("aids") val aids: JsonElement = JsonNull,
    )

    @Serializable
    data class ConnectPostMessage(
        val isBase64Publish: Boolean = false,
        val messageId: Long = 65536,
        val payload: String,
        val qos: Int = 1,
        val topic: String,
    )

    @Serializable
    data class AppSettingsPublish(
        @SerialName("ls_fdid") val lsFdid: String = "",
        @SerialName("ls_sv") val schemaVersion: String,
    )

    fun buildConnectJson(
        accountId: String,
        sessionId: Long,
        appId: Long,
        cid: String,
        platform: MetaAuthData.Platform,
        subscribedTopics: List<String> = emptyList(),
        hostNameOverride: String = "",
        previouslyConnected: Boolean = false,
        versionId: Long = 0,
    ): String {
        val connectionType = when (platform) {
            MetaAuthData.Platform.INSTAGRAM -> "cookie_auth"
            MetaAuthData.Platform.MESSENGER -> "websocket"
        }

        var topics = subscribedTopics.toMutableList()
        var postMessages = emptyList<ConnectPostMessage>()

        if (previouslyConnected) {
            val appSettingsJson = json.encodeToString(
                AppSettingsPublish(schemaVersion = versionId.toString())
            )
            postMessages = listOf(
                ConnectPostMessage(
                    payload = appSettingsJson,
                    topic = TOPIC_LS_APP_SETTINGS,
                )
            )
            if (TOPIC_LS_FOREGROUND_STATE !in topics) topics.add(TOPIC_LS_FOREGROUND_STATE)
            if (TOPIC_LS_RESP !in topics) topics.add(TOPIC_LS_RESP)
        }

        val connectPayload = ConnectJson(
            accountId = accountId,
            sessionId = sessionId,
            cid = cid,
            connectionType = connectionType,
            appId = appId,
            subscribedTopics = topics,
            postMessage = postMessages,
            hostNameOverride = hostNameOverride,
            userAgent = USER_AGENT,
        )

        return json.encodeToString(connectPayload)
    }

    const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    // --- Task payloads (from messagix/socket/threads.go, messages.go) ---

    @Serializable
    data class TaskPayload(
        @SerialName("epoch_id") val epochId: Long,
        @SerialName("data_trace_id") val dataTraceId: String = "",
        val tasks: List<TaskData> = emptyList(),
        @SerialName("version_id") val versionId: String,
    )

    @Serializable
    data class TaskData(
        @SerialName("failure_count") val failureCount: JsonElement = JsonNull,
        val label: String,
        val payload: String,
        @SerialName("queue_name") val queueName: JsonElement,
        @SerialName("task_id") val taskId: Long,
    )

    @Serializable
    data class DatabaseQuery(
        val database: Long,
        @SerialName("last_applied_cursor") val lastAppliedCursor: String? = null,
        @SerialName("sync_params") val syncParams: String? = null,
        @SerialName("epoch_id") val epochId: Long,
        @SerialName("data_trace_id") val dataTraceId: String = "",
        val version: String,
        @SerialName("failure_count") val failureCount: JsonElement = JsonNull,
    )

    @Serializable
    data class SendMessageTask(
        @SerialName("thread_id") val threadId: Long,
        @SerialName("otid") val otid: String,
        val source: Int = 0,
        @SerialName("send_type") val sendType: Int = 1,
        @SerialName("attachment_fbids") val attachmentFbIds: List<Long>? = null,
        @SerialName("sync_group") val syncGroup: Long = 1,
        @SerialName("reply_metadata") val replyMetadata: ReplyMetaData? = null,
        @SerialName("mention_data") val mentionData: MentionData? = null,
        val text: String = "",
        @SerialName("hot_emoji_size") val hotEmojiSize: Int = 0,
        @SerialName("sticker_id") val stickerId: Long = 0,
        @SerialName("initiating_source") val initiatingSource: Int = 0,
        @SerialName("skip_url_preview_gen") val skipUrlPreviewGen: Int = 0,
        @SerialName("text_has_links") val textHasLinks: Int = 0,
        @SerialName("strip_forwarded_msg_caption") val stripForwardedMsgCaption: Int = 0,
        @SerialName("forwarded_msg_id") val forwardedMsgId: String = "",
        @SerialName("multitab_env") val multitabEnv: Int = 0,
        val url: String = "",
        @SerialName("attribution_app_id") val attributionAppId: Long = 0,
    )

    @Serializable
    data class ReplyMetaData(
        @SerialName("reply_source_id") val replySourceId: String,
        @SerialName("reply_source_type") val replySourceType: Long = 1,
        @SerialName("reply_type") val replyType: Long = 0,
    )

    @Serializable
    data class MentionData(
        @SerialName("mention_ids") val mentionIds: String,
        @SerialName("mention_offsets") val mentionOffsets: String,
        @SerialName("mention_lengths") val mentionLengths: String,
        @SerialName("mention_types") val mentionTypes: String,
    )

    @Serializable
    data class SendReactionTask(
        @SerialName("thread_key") val threadKey: Long = 0,
        @SerialName("timestamp_ms") val timestampMs: Long = System.currentTimeMillis(),
        @SerialName("message_id") val messageId: String,
        @SerialName("actor_id") val actorId: Long,
        val reaction: String,
        @SerialName("reaction_style") val reactionStyle: JsonElement = JsonNull,
        @SerialName("sync_group") val syncGroup: Int = 1,
        @SerialName("send_attribution") val sendAttribution: Int = 0,
    )

    @Serializable
    data class ThreadMarkReadTask(
        @SerialName("thread_id") val threadId: Long,
        @SerialName("last_read_watermark_ts") val lastReadWatermarkTs: Long,
        @SerialName("sync_group") val syncGroup: Long = 1,
    )

    @Serializable
    data class FetchThreadsTask(
        @SerialName("is_after") val isAfter: Int = 0,
        @SerialName("parent_thread_key") val parentThreadKey: Long = -1,
        @SerialName("reference_thread_key") val referenceThreadKey: Long = 0,
        @SerialName("reference_activity_timestamp") val referenceActivityTimestamp: Long = 9999999999999,
        @SerialName("additional_pages_to_fetch") val additionalPagesToFetch: Int = 0,
        val cursor: JsonElement = JsonNull,
        @SerialName("messaging_tag") val messagingTag: JsonElement = JsonNull,
        @SerialName("sync_group") val syncGroup: Int = 1,
    )

    @Serializable
    data class FetchMessagesTask(
        @SerialName("thread_key") val threadKey: Long,
        val direction: Long = 0,
        @SerialName("reference_timestamp_ms") val referenceTimestampMs: Long,
        @SerialName("reference_message_id") val referenceMessageId: String,
        @SerialName("sync_group") val syncGroup: Long = 1,
        val cursor: String = "",
    )

    @Serializable
    data class ReportAppStateTask(
        @SerialName("app_state") val appState: Int = 1, // FOREGROUND
        @SerialName("request_id") val requestId: String,
    )

    // Task labels (from messagix/socket/task.go)
    val TASK_LABELS = mapOf(
        "UpdatePresence" to "3",
        "ThreadMarkRead" to "21",
        "AddParticipantsTask" to "23",
        "UpdateAdminTask" to "25",
        "SendReactionTask" to "29",
        "SearchUserTask" to "30",
        "SearchUserSecondaryTask" to "31",
        "RenameThreadTask" to "32",
        "DeleteMessageTask" to "33",
        "SetThreadImageTask" to "37",
        "SendMessageTask" to "46",
        "ReportAppStateTask" to "123",
        "CreateGroupTask" to "130",
        "RemoveParticipantTask" to "140",
        "MuteThreadTask" to "144",
        "FetchThreadsTask" to "145",
        "DeleteThreadTask" to "146",
        "DeleteMessageMeOnlyTask" to "155",
        "CreatePollTask" to "163",
        "UpdatePollTask" to "164",
        "GetContactsFullTask" to "207",
        "CreateThreadTask" to "209",
        "FetchMessagesTask" to "228",
        "FetchCommunityMemberList" to "355",
        "CreateWhatsAppThreadTask" to "388",
        "GetContactsTask" to "452",
        "CommunityThreadHoleDetection" to "501",
        "FetchReactionsV2UserList" to "577",
        "SendReactionV2" to "604",
        "DeleteCommunitySubThread" to "639",
        "CreateCommunitySubThread" to "665",
        "FetchAdditionalThreadData" to "733",
        "EditMessageTask" to "742",
    )

    // --- LS Request wrapper (from messagix/socket.go) ---

    @Serializable
    data class SocketLSRequestPayload(
        @SerialName("app_id") val appId: String,
        val payload: String,
        @SerialName("request_id") val requestId: Int,
        val type: Int,
    )

    // --- Parsed incoming message ---

    data class MetaMessage(
        val messageId: String,
        val threadId: String,
        val senderId: String,
        val senderName: String?,
        val text: String,
        val timestamp: Long,
        val isGroup: Boolean,
    )

    data class MqttMessage(
        val topic: String,
        val payload: ByteArray,
        val packetId: Int = 0,
        val qos: Int = 0,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MqttMessage) return false
            return topic == other.topic && payload.contentEquals(other.payload) &&
                packetId == other.packetId && qos == other.qos
        }
        override fun hashCode(): Int {
            var result = topic.hashCode()
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + packetId
            result = 31 * result + qos
            return result
        }
    }

    fun parsePublishResponse(payload: ByteArray): LightspeedDecoder.PublishResponseData? {
        return try {
            val jsonStr = String(payload, Charsets.UTF_8)
            json.decodeFromString<LightspeedDecoder.PublishResponseData>(jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    fun parseMessage(events: List<LightspeedDecoder.DecodedEvent>): MetaMessage? {
        for (event in events) {
            when (event.procedureName) {
                "LSInsertNewMessageRange",
                "LSUpsertMessage",
                "LSInsertMessage",
                "LSAddNewMessage",
                -> {
                    val args = event.args
                    if (args.size < 5) continue
                    val text = args.getOrNull(0)?.toString() ?: ""
                    val threadId = args.getOrNull(1)?.toString() ?: continue
                    val messageId = args.getOrNull(2)?.toString() ?: continue
                    val timestamp = (args.getOrNull(3) as? Long) ?: System.currentTimeMillis()
                    val senderId = args.getOrNull(4)?.toString() ?: ""
                    val senderName = args.getOrNull(5)?.toString()?.takeIf { it.isNotBlank() }
                    val isGroup = (threadId.toLongOrNull() ?: 0) < 0

                    return MetaMessage(
                        messageId = messageId,
                        threadId = threadId,
                        senderId = senderId,
                        senderName = senderName,
                        text = text,
                        timestamp = timestamp,
                        isGroup = isGroup,
                    )
                }
            }
        }
        return null
    }

    fun buildTaskPayload(
        label: String,
        taskPayloadJson: String,
        queueName: Any,
        versionId: Long,
    ): String {
        val taskId = getTaskId()
        val epochId = generateEpochId()

        val queueNameElement: JsonElement = when (queueName) {
            is String -> JsonPrimitive(queueName)
            is List<*> -> {
                val arr = queueName.filterIsInstance<String>()
                val encoded = json.encodeToString(arr)
                JsonPrimitive(encoded)
            }
            else -> JsonPrimitive(queueName.toString())
        }

        val payload = TaskPayload(
            epochId = epochId,
            versionId = versionId.toString(),
            tasks = listOf(
                TaskData(
                    label = label,
                    payload = taskPayloadJson,
                    queueName = queueNameElement,
                    taskId = taskId,
                )
            ),
        )
        return json.encodeToString(payload)
    }

    fun buildSendMessagePayload(threadId: Long, text: String, versionId: Long): String {
        val otid = generateEpochId().toString()
        val task = SendMessageTask(
            threadId = threadId,
            otid = otid,
            text = text,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["SendMessageTask"] ?: "46",
            taskPayloadJson = taskJson,
            queueName = threadId.toString(),
            versionId = versionId,
        )
    }

    fun buildMarkReadPayload(threadId: Long, versionId: Long): String {
        val task = ThreadMarkReadTask(
            threadId = threadId,
            lastReadWatermarkTs = System.currentTimeMillis(),
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["ThreadMarkRead"] ?: "21",
            taskPayloadJson = taskJson,
            queueName = threadId.toString(),
            versionId = versionId,
        )
    }

    fun buildReactionPayload(
        threadKey: Long,
        messageId: String,
        reaction: String,
        actorId: Long,
        versionId: Long,
    ): String {
        val task = SendReactionTask(
            threadKey = threadKey,
            messageId = messageId,
            reaction = reaction,
            actorId = actorId,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["SendReactionTask"] ?: "29",
            taskPayloadJson = taskJson,
            queueName = listOf("reaction", messageId),
            versionId = versionId,
        )
    }

    fun buildDatabaseQueryPayload(databaseId: Long, versionId: Long, cursor: String? = null): String {
        val query = DatabaseQuery(
            database = databaseId,
            epochId = generateEpochId(),
            version = versionId.toString(),
            lastAppliedCursor = cursor,
        )
        return json.encodeToString(query)
    }

    fun buildLSRequestJson(appId: String, payload: String, requestId: Int, type: Int): String {
        val lsReq = SocketLSRequestPayload(
            appId = appId,
            payload = payload,
            requestId = requestId,
            type = type,
        )
        return json.encodeToString(lsReq)
    }

    @Serializable
    data class DeleteMessageTask(
        @SerialName("message_id") val messageId: String,
    )

    @Serializable
    data class DeleteMessageMeOnlyTask(
        @SerialName("thread_key") val threadKey: Long = 0,
        @SerialName("message_id") val messageId: String,
    )

    @Serializable
    data class EditMessageTask(
        @SerialName("message_id") val messageId: String,
        val text: String,
    )

    fun buildFetchThreadsPayload(versionId: Long, syncGroup: Int = 1): String {
        val task = FetchThreadsTask(syncGroup = syncGroup)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["FetchThreadsTask"] ?: "145",
            taskPayloadJson = taskJson,
            queueName = "trq",
            versionId = versionId,
        )
    }

    fun buildFetchMessagesPayload(
        threadKey: Long,
        referenceTimestampMs: Long,
        referenceMessageId: String,
        versionId: Long,
    ): String {
        val task = FetchMessagesTask(
            threadKey = threadKey,
            referenceTimestampMs = referenceTimestampMs,
            referenceMessageId = referenceMessageId,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["FetchMessagesTask"] ?: "228",
            taskPayloadJson = taskJson,
            queueName = "mrq.$threadKey",
            versionId = versionId,
        )
    }

    fun buildReportAppStatePayload(versionId: Long): String {
        val task = ReportAppStateTask(
            requestId = java.util.UUID.randomUUID().toString(),
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["ReportAppStateTask"] ?: "123",
            taskPayloadJson = taskJson,
            queueName = "ls_presence_report_app_state",
            versionId = versionId,
        )
    }

    fun buildDeleteMessagePayload(
        messageId: String,
        versionId: Long,
    ): String {
        val task = DeleteMessageTask(
            messageId = messageId,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["DeleteMessageTask"] ?: "33",
            taskPayloadJson = taskJson,
            queueName = "unsend_message",
            versionId = versionId,
        )
    }

    fun buildDeleteMessageMeOnlyPayload(
        threadKey: Long,
        messageId: String,
        versionId: Long,
    ): String {
        val task = DeleteMessageMeOnlyTask(
            threadKey = threadKey,
            messageId = messageId,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["DeleteMessageMeOnlyTask"] ?: "155",
            taskPayloadJson = taskJson,
            queueName = "155",
            versionId = versionId,
        )
    }

    fun buildEditMessagePayload(
        messageId: String,
        text: String,
        versionId: Long,
    ): String {
        val task = EditMessageTask(
            messageId = messageId,
            text = text,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["EditMessageTask"] ?: "742",
            taskPayloadJson = taskJson,
            queueName = "edit_message",
            versionId = versionId,
        )
    }

    @Serializable
    data class DeleteThreadTask(
        @SerialName("thread_key") val threadKey: Long,
        @SerialName("remove_type") val removeType: Long = 0,
        @SerialName("sync_group") val syncGroup: Long = 1,
    )

    fun buildDeleteThreadPayload(
        threadKey: Long,
        versionId: Long,
    ): String {
        val task = DeleteThreadTask(threadKey = threadKey)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["DeleteThreadTask"] ?: "146",
            taskPayloadJson = taskJson,
            queueName = threadKey.toString(),
            versionId = versionId,
        )
    }

    @Serializable
    data class MuteThreadTask(
        @SerialName("thread_key") val threadKey: Long,
        @SerialName("mailbox_type") val mailboxType: Long = 0,
        @SerialName("mute_expire_time_ms") val muteExpireTimeMs: Long,
        @SerialName("sync_group") val syncGroup: Long = 1,
    )

    fun buildMuteThreadPayload(
        threadKey: Long,
        muteExpireTimeMs: Long,
        versionId: Long,
    ): String {
        val task = MuteThreadTask(threadKey = threadKey, muteExpireTimeMs = muteExpireTimeMs)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["MuteThreadTask"] ?: "144",
            taskPayloadJson = taskJson,
            queueName = threadKey.toString(),
            versionId = versionId,
        )
    }

    @Serializable
    data class RenameThreadTask(
        @SerialName("thread_key") val threadKey: Long,
        @SerialName("thread_name") val threadName: String,
        @SerialName("sync_group") val syncGroup: Long = 1,
    )

    fun buildRenameThreadPayload(
        threadKey: Long,
        threadName: String,
        versionId: Long,
    ): String {
        val task = RenameThreadTask(threadKey = threadKey, threadName = threadName)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["RenameThreadTask"] ?: "32",
            taskPayloadJson = taskJson,
            queueName = threadKey.toString(),
            versionId = versionId,
        )
    }

    fun buildAppSettingsJson(versionId: Long): String {
        return json.encodeToString(AppSettingsPublish(schemaVersion = versionId.toString()))
    }

    private var lastTimestamp = 0L
    private var epochCounter = 0L
    private val epochLock = Any()

    fun generateEpochId(): Long {
        synchronized(epochLock) {
            val timestamp = System.currentTimeMillis()
            if (timestamp == lastTimestamp) {
                epochCounter++
            } else {
                lastTimestamp = timestamp
                epochCounter = 0
            }
            return (timestamp shl 22) or (epochCounter shl 12) or 42
        }
    }

    fun generateSessionId(): Long {
        val min = 2171078810009599L
        val max = 4613554604867583L
        return min + (Math.random() * (max - min + 1)).toLong()
    }
}
