package com.vayunmathur.vpn.domain

import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Optional freshness metadata carried by a `mavpn/1` manifest (and retained
 * on every imported config row). All fields are optional so legacy bundles
 * without any of them keep importing exactly as before.
 *
 * Timestamps are epoch milliseconds parsed from ISO-8601 UTC instants
 * (`2026-01-01T00:00:00Z`); `revision` is a provider-monotonic counter.
 */
data class BundleFreshness(
    val providerId: String? = null,
    val revision: Long? = null,
    val issuedAtEpochMs: Long? = null,
    val expiresAtEpochMs: Long? = null,
    val updateUrl: String? = null,
) {
    /** True when the bundle carried no freshness metadata at all (legacy bundle). */
    val isEmpty: Boolean
        get() = providerId == null && revision == null && issuedAtEpochMs == null &&
            expiresAtEpochMs == null && updateUrl == null

    /** Shared constants. */
    companion object {
        val EMPTY = BundleFreshness()
    }
}

/** Local/remote freshness states shown in the provider and picker UI. */
enum class BundleFreshnessState {
    /** Metadata present, not expired, and no newer remote revision is known. */
    CURRENT,

    /** `expiresAt` has passed. A warning only: servers remain usable. */
    OUTDATED,

    /** A checked update endpoint reports a newer revision. */
    UPDATE_AVAILABLE,

    /** Legacy bundle or metadata too thin to judge (no expiry and no revision). */
    UNKNOWN,

    /** The last user-triggered update check failed (network, parse or mismatch). */
    CHECK_FAILED,
}

/**
 * Classifies [freshness] at [nowEpochMs]. Never claims [BundleFreshnessState.CURRENT]
 * from age alone: current requires unexpired `expiresAt` metadata or a known
 * revision with no newer remote revision. Expiry is a warning state only.
 */
fun classifyFreshness(
    freshness: BundleFreshness,
    nowEpochMs: Long,
    remoteRevision: Long? = null,
    checkFailed: Boolean = false,
): BundleFreshnessState = when {
    checkFailed -> BundleFreshnessState.CHECK_FAILED
    remoteRevision != null && (freshness.revision == null || remoteRevision > freshness.revision) ->
        BundleFreshnessState.UPDATE_AVAILABLE
    freshness.expiresAtEpochMs != null && nowEpochMs >= freshness.expiresAtEpochMs ->
        BundleFreshnessState.OUTDATED
    freshness.isEmpty -> BundleFreshnessState.UNKNOWN
    freshness.expiresAtEpochMs == null && freshness.revision == null -> BundleFreshnessState.UNKNOWN
    else -> BundleFreshnessState.CURRENT
}

/** Parsed `mavpn-update/1` freshness metadata served from a bundle's `updateUrl`. */
data class MavpnUpdateMetadata(
    val format: String,
    val providerId: String,
    val revision: Long,
    val issuedAtEpochMs: Long? = null,
    val expiresAtEpochMs: Long? = null,
    val downloadUrl: String? = null,
    val sha256: String? = null,
) {
    fun toFreshness(updateUrl: String? = null): BundleFreshness = BundleFreshness(
        providerId = providerId,
        revision = revision,
        issuedAtEpochMs = issuedAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        updateUrl = updateUrl,
    )
}

/** Result of comparing local bundle freshness against remote update metadata. */
sealed interface UpdateComparison {
    /** Remote revision is not newer than the local one. */
    data object UpToDate : UpdateComparison

    /** Remote revision is newer; [remote] carries its metadata. */
    data class UpdateAvailable(val remote: MavpnUpdateMetadata) : UpdateComparison

    /** Remote metadata names a different provider; it is never applied. */
    data class ProviderMismatch(val localProviderId: String?, val remoteProviderId: String) : UpdateComparison
}

/** Compares [local] with [remote]; a provider mismatch never counts as an update. */
fun compareUpdate(local: BundleFreshness, remote: MavpnUpdateMetadata): UpdateComparison = when {
    local.providerId != null && local.providerId != remote.providerId ->
        UpdateComparison.ProviderMismatch(local.providerId, remote.providerId)
    local.revision != null && remote.revision <= local.revision -> UpdateComparison.UpToDate
    else -> UpdateComparison.UpdateAvailable(remote)
}

/** Lower-case hex SHA-256 of [bytes]. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** True when [bytes] hash to [expectedHex] (case-insensitive); false for malformed expectations. */
fun verifySha256(bytes: ByteArray, expectedHex: String): Boolean {
    val expected = expectedHex.trim().lowercase()
    if (!SHA256_HEX.matches(expected)) return false
    return MessageDigest.isEqual(sha256Hex(bytes).encodeToByteArray(), expected.encodeToByteArray())
}

private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val PROVIDER_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

internal fun parseProviderId(raw: String?, field: String): String? {
    if (raw == null) return null
    val value = raw.trim()
    if (value.isEmpty() || !PROVIDER_ID_PATTERN.matches(value)) {
        throw MavpnFormatException("Invalid $field '$raw'")
    }
    return value
}

internal fun parseRevision(raw: String?, field: String): Long? {
    if (raw == null) return null
    val value = raw.trim()
    val parsed = value.toLongOrNull()
    // Reject floats/exponents and negatives: revision is a monotonic counter.
    if (parsed == null || value != parsed.toString() || parsed < 0) {
        throw MavpnFormatException("Invalid $field '$raw' (expected a non-negative integer)")
    }
    return parsed
}

internal fun parseInstantEpochMs(raw: String?, field: String): Long? {
    if (raw == null) return null
    return runCatching { Instant.parse(raw.trim()).toEpochMilli() }.getOrElse { error ->
        val why = if (error is DateTimeParseException) {
            "expected ISO-8601 UTC, e.g. 2026-01-01T00:00:00Z"
        } else {
            "timestamp out of range"
        }
        throw MavpnFormatException("Invalid $field '$raw' ($why)")
    }
}

internal fun checkExpiryAfterIssue(issuedAtEpochMs: Long?, expiresAtEpochMs: Long?) {
    if (issuedAtEpochMs != null && expiresAtEpochMs != null && expiresAtEpochMs <= issuedAtEpochMs) {
        throw MavpnFormatException("expiresAt must be after issuedAt")
    }
}

/**
 * Validates an HTTPS URL field. Non-HTTPS URLs are rejected with no test seam
 * in the parsers: tests inject fake fetchers instead of weakening TLS/URL rules.
 */
internal fun parseHttpsUrl(raw: String?, field: String): String? {
    if (raw == null) return null
    val value = raw.trim()
    if (!isHttpsUrl(value)) throw MavpnFormatException("Invalid $field (expected an https:// URL)")
    return value
}

internal fun isHttpsUrl(value: String): Boolean {
    if (value.any { it.isWhitespace() } || !value.startsWith("https://")) return false
    val host = value.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
    return host.isNotBlank() && !host.startsWith(".") && !host.endsWith(".")
}

internal fun JsonObject.optionalString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** Parses and validates a `mavpn-update/1` metadata document. */
fun parseMavpnUpdateMetadata(text: String): MavpnUpdateMetadata {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
        throw MavpnFormatException("Update metadata is not valid JSON")
    }
    val format = root.optionalString("format") ?: throw MavpnFormatException("Update metadata is missing 'format'")
    if (format != MavpnUpdateParser.FORMAT) throw MavpnFormatException("Unsupported update format '$format'")
    val providerId = parseProviderId(root.optionalString("providerId"), "providerId")
        ?: throw MavpnFormatException("Update metadata is missing 'providerId'")
    val revision = parseRevision(root.optionalString("revision"), "revision")
        ?: throw MavpnFormatException("Update metadata is missing 'revision'")
    val issued = parseInstantEpochMs(root.optionalString("issuedAt"), "issuedAt")
    val expires = parseInstantEpochMs(root.optionalString("expiresAt"), "expiresAt")
    checkExpiryAfterIssue(issued, expires)
    val sha = root.optionalString("sha256")?.trim()?.lowercase()
    if (sha != null && !SHA256_HEX.matches(sha)) throw MavpnFormatException("Invalid sha256 in update metadata")
    return MavpnUpdateMetadata(
        format = format,
        providerId = providerId,
        revision = revision,
        issuedAtEpochMs = issued,
        expiresAtEpochMs = expires,
        downloadUrl = parseHttpsUrl(root.optionalString("downloadUrl"), "downloadUrl"),
        sha256 = sha,
    )
}

/** Constants for the update-metadata format. */
object MavpnUpdateParser {
    const val FORMAT = "mavpn-update/1"
}
