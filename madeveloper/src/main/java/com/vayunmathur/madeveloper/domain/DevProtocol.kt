package com.vayunmathur.madeveloper.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/** A server-to-device message on the dev push socket. */
sealed interface DevMessage {
    /** The server is alive; the client answers with a pong frame. */
    data object Ping : DevMessage
    /** The server asks which first-party packages are installed. */
    data object ListApps : DevMessage
    /** The server has paired this device and issued a durable token. */
    data class Paired(val token: String, val deviceId: String) : DevMessage
    /** A build the server wants installed on this device. */
    data class Install(
        val pkg: String, val versionCode: Long, val apkUrl: String,
        val sha256: String, val sizeBytes: Long,
    ) : DevMessage
    /** Anything else: kept verbatim so a new server type never crashes the client. */
    data class Unknown(val raw: String) : DevMessage
}

/** One installed first-party package reported back to the server. */
data class InstalledAppInfo(val pkg: String, val versionCode: Long)

/** Lifecycle of one pushed install, from push to the system installer. */
enum class InstallPhase { PUSHED, DOWNLOADING, VERIFYING, READY, INSTALLING, DONE, FAILED }

/** State of one pushed install; [progress] is 0f..1f while downloading. */
data class PendingInstall(
    val pkg: String, val versionCode: Long, val apkUrl: String, val sha256: String, val sizeBytes: Long,
    val phase: InstallPhase = InstallPhase.PUSHED, val progress: Float = 0f, val error: String? = null,
)

/** Pure protocol and install-state logic for the dev push channel. */
object DevProtocol {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val CODE_LENGTH = 6
    private const val MAX_BACKOFF_MS = 60_000L
    private const val BASE_BACKOFF_MS = 1_000L

    /** A short human-typable pairing code shown on first launch. */
    fun newPairingCode(random: java.util.Random = java.security.SecureRandom()): String =
        buildString { repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) } }

    /** Parses one text frame from the server; malformed frames become [DevMessage.Unknown]. */
    fun parse(text: String): DevMessage {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return DevMessage.Unknown(text)
        }
        return when (root.s("type")) {
            "ping" -> DevMessage.Ping
            "listApps" -> DevMessage.ListApps
            "paired" -> DevMessage.Paired(root.s("token"), root.s("deviceId"))
            "install" -> DevMessage.Install(
                pkg = root.s("package"), versionCode = root["versionCode"]?.jsonPrimitive?.longOrNull ?: 0L,
                apkUrl = root.s("apkUrl"), sha256 = root.s("sha256"),
                sizeBytes = root["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
            else -> DevMessage.Unknown(text)
        }
    }

    /** The reply to a [DevMessage.ListApps] request. */
    fun listAppsReply(apps: List<InstalledAppInfo>): String = buildString {
        append("{\"type\":\"apps\",\"apps\":[")
        apps.forEachIndexed { index, app ->
            if (index > 0) append(',')
            append("{\"package\":\"").append(app.pkg).append("\",\"versionCode\":").append(app.versionCode).append('}')
        }
        append("]}")
    }

    /** Exponential reconnect backoff in milliseconds, capped, by 0-based attempt. */
    fun backoffMs(attempt: Int): Long {
        var delay = BASE_BACKOFF_MS
        repeat(attempt.coerceIn(0, 20)) { delay = (delay * 2).coerceAtMost(MAX_BACKOFF_MS) }
        return delay
    }

    /** Advances the install state machine; illegal jumps keep the current phase. */
    fun transition(
        current: PendingInstall, phase: InstallPhase,
        progress: Float = current.progress, error: String? = null,
    ): PendingInstall {
        val allowed = when (current.phase) {
            InstallPhase.PUSHED -> phase in setOf(InstallPhase.DOWNLOADING, InstallPhase.FAILED)
            InstallPhase.DOWNLOADING -> phase in setOf(InstallPhase.VERIFYING, InstallPhase.FAILED)
            InstallPhase.VERIFYING -> phase in setOf(InstallPhase.READY, InstallPhase.FAILED)
            InstallPhase.READY -> phase in setOf(InstallPhase.INSTALLING, InstallPhase.FAILED)
            InstallPhase.INSTALLING -> phase in setOf(InstallPhase.DONE, InstallPhase.FAILED, InstallPhase.READY)
            InstallPhase.DONE, InstallPhase.FAILED -> false
        }
        return if (allowed) {
            current.copy(phase = phase, progress = progress.coerceIn(0f, 1f), error = error)
        } else {
            current
        }
    }

    /** SHA-256 of [bytes] as lowercase hex, for comparison with a pushed digest. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** True when [bytes] hash to the expected lowercase/uppercase hex digest. */
    fun verifySha256(bytes: ByteArray, expected: String): Boolean =
        expected.isNotBlank() && sha256Hex(bytes).equals(expected.trim(), ignoreCase = true)

    private fun JsonObject.s(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
}
