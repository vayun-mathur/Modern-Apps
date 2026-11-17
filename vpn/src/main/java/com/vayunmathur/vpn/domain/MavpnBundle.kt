package com.vayunmathur.vpn.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One server entry from a `.mavpn` bundle manifest (`mavpn/1`). See `vpn/docs/MAVPN.md`. */
data class MavpnServerEntry(
    val id: String,
    val country: String,
    val city: String,
    val file: String,
    val endpoint: String? = null,
)

/** The parsed `manifest.json` of a `.mavpn` bundle. */
data class MavpnManifest(
    val format: String,
    val provider: String,
    val servers: List<MavpnServerEntry>,
    /** Optional freshness metadata; [BundleFreshness.EMPTY] for legacy bundles. */
    val freshness: BundleFreshness = BundleFreshness.EMPTY,
)

/** A manifest entry together with its WireGuard config text and its parsed config. */
data class ParsedMavpnServer<T>(
    val entry: MavpnServerEntry,
    val confText: String,
    val config: T,
) {
    /** Endpoint shown and used for activation: the manifest override wins over the `.conf`. */
    fun effectiveEndpoint(confEndpoint: (T) -> String): String =
        entry.endpoint?.takeIf { it.isNotBlank() } ?: confEndpoint(config)
}

/** A fully validated `.mavpn` bundle: manifest plus every referenced, parsed server config. */
data class MavpnBundle<T>(
    val manifest: MavpnManifest,
    val servers: List<ParsedMavpnServer<T>>,
)

/** Why a `.mavpn` bundle was rejected. Messages never contain config (key) material. */
class MavpnFormatException(message: String) : IllegalArgumentException(message)

/**
 * Pure parser for `.mavpn` bundles (ZIP + `manifest.json` + `servers/<id>.conf`).
 *
 * No Android APIs: callers inject the app's existing WireGuard parser as
 * [MavpnParser.parse]'s `parseConf`, so this module never grows a second
 * WireGuard parser. Unzipping is defensive: entry count, per-entry size and
 * total size are capped and any path that would escape the bundle root
 * (absolute paths, `..` segments, backslash separators) is rejected before a
 * single byte of it is read.
 */
object MavpnParser {
    const val FORMAT = "mavpn/1"
    const val MANIFEST_NAME = "manifest.json"
    const val MAX_ENTRIES = 256
    const val MAX_SERVERS = 200
    const val MAX_ENTRY_BYTES = 1_048_576
    const val MAX_TOTAL_BYTES = 8_388_608
    const val MAX_MANIFEST_BYTES = 262_144

    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val serverFilePattern = Regex("servers/[A-Za-z0-9][A-Za-z0-9._-]*\\.conf")
    private val isoCountries: Set<String> by lazy { Locale.getISOCountries().toSet() }

    /**
     * Parses [bytes] as a `.mavpn` bundle, loading each referenced `.conf`
     * through [parseConf]. Fails with [MavpnFormatException] on any format,
     * safety or WireGuard problem; the underlying parser's error is wrapped
     * without echoing config contents.
     */
    fun <T> parse(bytes: ByteArray, parseConf: (String) -> Result<T>): Result<MavpnBundle<T>> = runCatching {
        val entries = unzip(bytes)
        val manifestText = entries[MANIFEST_NAME]
            ?: throw MavpnFormatException("Missing $MANIFEST_NAME at bundle root")
        val manifest = parseManifest(manifestText.decodeToString())
        val servers = manifest.servers.map { entry -> loadServer(entry, entries, parseConf) }
        MavpnBundle(manifest, servers)
    }.recoverCatching { error ->
        if (error is MavpnFormatException) throw error
        throw MavpnFormatException(error.message ?: "Invalid .mavpn bundle")
    }

    private fun <T> loadServer(
        entry: MavpnServerEntry,
        entries: Map<String, ByteArray>,
        parseConf: (String) -> Result<T>,
    ): ParsedMavpnServer<T> {
        val confBytes = entries[entry.file]
            ?: throw MavpnFormatException("Server '${entry.id}' references missing file '${entry.file}'")
        val confText = confBytes.decodeToString()
        val config = parseConf(confText).getOrElse {
            throw MavpnFormatException("Server '${entry.id}' has an invalid WireGuard config")
        }
        return ParsedMavpnServer(entry, confText, config)
    }

    /** Reads the whole ZIP into memory under the size/count caps, or throws [MavpnFormatException]. */
    internal fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (entries.size >= MAX_ENTRIES) {
                        throw MavpnFormatException("Too many entries (limit $MAX_ENTRIES)")
                    }
                    val name = checkedEntryName(entry.name)
                    if (entry.isDirectory) continue
                    if (entries.containsKey(name)) throw MavpnFormatException("Duplicate entry '$name'")
                    val cap = if (name == MANIFEST_NAME) MAX_MANIFEST_BYTES else MAX_ENTRY_BYTES
                    val data = readCapped(zip, cap, name)
                    total += data.size
                    if (total > MAX_TOTAL_BYTES) {
                        throw MavpnFormatException("Bundle too large (limit $MAX_TOTAL_BYTES bytes)")
                    }
                    entries[name] = data
                } finally {
                    zip.closeEntry()
                }
            }
        }
        if (entries.isEmpty()) throw MavpnFormatException("Empty bundle")
        return entries
    }

    private fun readCapped(zip: ZipInputStream, cap: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > cap) throw MavpnFormatException("Entry '$name' too large (limit $cap bytes)")
        }
        return out.toByteArray()
    }

    /** Rejects absolute paths, drive-style/backslash separators and `..` traversal segments. */
    internal fun checkedEntryName(rawName: String): String {
        val name = rawName.trim()
        if (name.isEmpty()) throw MavpnFormatException("Empty entry name")
        if (name.startsWith("/") || name.startsWith("\\") || name.contains('\\')) {
            throw MavpnFormatException("Unsafe entry path '$rawName'")
        }
        if (name.split('/').any { it == ".." || it == "." }) {
            throw MavpnFormatException("Unsafe entry path '$rawName'")
        }
        if (name.length > 1 && name[1] == ':') throw MavpnFormatException("Unsafe entry path '$rawName'")
        return name
    }

    /** Parses and validates `manifest.json`; unknown fields are ignored within major 1. */
    internal fun parseManifest(text: String): MavpnManifest {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw MavpnFormatException("manifest.json is not valid JSON")
        }
        val format = root.requiredString("format")
        checkFormat(format)
        val provider = root.requiredString("provider")
        val serverArray = root["servers"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: throw MavpnFormatException("manifest.json is missing 'servers'")
        if (serverArray.isEmpty()) throw MavpnFormatException("manifest.json lists no servers")
        if (serverArray.size > MAX_SERVERS) {
            throw MavpnFormatException("Too many servers (limit $MAX_SERVERS)")
        }
        val servers = serverArray.map { element -> parseServer(element.jsonObject) }
        val duplicate = servers.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }?.key
        if (duplicate != null) throw MavpnFormatException("Duplicate server id '$duplicate'")
        return MavpnManifest(format, provider, servers, parseFreshness(root))
    }

    /** Optional `mavpn/1` freshness fields; absent fields stay null for legacy bundles. */
    private fun parseFreshness(root: JsonObject): BundleFreshness {
        val issued = parseInstantEpochMs(root.optionalString("issuedAt"), "issuedAt")
        val expires = parseInstantEpochMs(root.optionalString("expiresAt"), "expiresAt")
        checkExpiryAfterIssue(issued, expires)
        return BundleFreshness(
            providerId = parseProviderId(root.optionalString("providerId"), "providerId"),
            revision = parseRevision(root.optionalString("revision"), "revision"),
            issuedAtEpochMs = issued,
            expiresAtEpochMs = expires,
            updateUrl = parseHttpsUrl(root.optionalString("updateUrl"), "updateUrl"),
        )
    }

    private fun checkFormat(format: String) {
        val parts = format.split('/')
        if (parts.size != 2 || parts[0] != "mavpn") {
            throw MavpnFormatException("Unknown bundle format '$format'")
        }
        val major = parts[1].toIntOrNull()
            ?: throw MavpnFormatException("Unknown bundle format '$format'")
        if (major != 1) throw MavpnFormatException("Unsupported mavpn format major $major")
    }

    private fun parseServer(obj: JsonObject): MavpnServerEntry {
        val id = obj.requiredString("id")
        if (!idPattern.matches(id)) throw MavpnFormatException("Invalid server id '$id'")
        val country = obj.requiredString("country")
        if (country !in isoCountries) throw MavpnFormatException("Invalid country code '$country'")
        val city = obj.requiredString("city")
        val file = obj.requiredString("file")
        if (!serverFilePattern.matches(file)) {
            throw MavpnFormatException("Invalid server file '$file' (expected servers/<name>.conf)")
        }
        val endpoint = obj["endpoint"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return MavpnServerEntry(id, country, city, file, endpoint)
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = this[key]?.jsonPrimitive?.contentOrNull
        if (value.isNullOrBlank()) throw MavpnFormatException("manifest.json is missing '$key'")
        return value.trim()
    }
}

/** Regional-indicator flag emoji for an ISO 3166-1 alpha-2 [countryCode], or the code itself. */
fun flagEmoji(countryCode: String): String {
    val code = countryCode.uppercase(Locale.ROOT)
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return code
    val first = Character.toChars(REGIONAL_INDICATOR_BASE + (code[0] - 'A'))
    val second = Character.toChars(REGIONAL_INDICATOR_BASE + (code[1] - 'A'))
    return String(first) + String(second)
}

/** Localised country name for [countryCode] in [locale] (the platform locale by default). */
fun countryDisplayName(countryCode: String, locale: Locale = Locale.getDefault()): String {
    val name = Locale.Builder().setRegion(countryCode.uppercase(Locale.ROOT)).build()
        .getDisplayCountry(locale)
    return name.ifBlank { countryCode.uppercase(Locale.ROOT) }
}

private const val REGIONAL_INDICATOR_BASE = 0x1F1E6
