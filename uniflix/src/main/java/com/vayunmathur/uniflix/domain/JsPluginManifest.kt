package com.vayunmathur.uniflix.domain

/** Maximum accepted plugin file size (covers ~90KB base64 wasm + JS with headroom). */
const val JS_PLUGIN_MAX_CHARS = 512 * 1024

private const val HEADER_PREFIX = "@uniflix-plugin"
private const val WASM_BEGIN = "@uniflix-wasm-begin"
private const val WASM_END = "@uniflix-wasm-end"
private val ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")

/**
 * Identity + sources of an installable JavaScript plugin.
 *
 * The file declares itself in a leading comment header:
 * `// @uniflix-plugin id=<id> name=<name> version=<version>`
 * followed by the plugin JS. An optional WASM section carries embedded
 * module bytes (e.g. a seal implementation) as base64 between
 * `// @uniflix-wasm-begin` and `// @uniflix-wasm-end` markers:
 *
 * The host ([data.JsPluginEngine]) exposes capabilities only; all provider
 * logic lives in [jsSource]. Pure (no Android) so parsing is unit-testable.
 */
data class JsPluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val jsSource: String,
    val wasmBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JsPluginManifest) return false
        return id == other.id && name == other.name && version == other.version &&
            jsSource == other.jsSource && (wasmBytes == null) == (other.wasmBytes == null)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + version.hashCode()
        return result
    }

    /** Shared constants and helpers for JsPluginManifest. */
    companion object {
        /** Parses and validates a plugin file; throws [ManifestError.Parse] when invalid. */
        fun parse(source: String): JsPluginManifest {
            if (source.length > JS_PLUGIN_MAX_CHARS) {
                throw ManifestError.Parse(
                    "Plugin file too large (${source.length} chars, max $JS_PLUGIN_MAX_CHARS)",
                )
            }
            val lines = source.lines()
            val header = lines.firstOrNull { HEADER_PREFIX in it }
                ?: throw ManifestError.Parse(
                    "Not a UniFlix plugin: missing '$HEADER_PREFIX id=.. name=.. version=..' header",
                )
            val id = field(header, "id") ?: throw ManifestError.Parse("Plugin header missing id=")
            val name = field(header, "name") ?: throw ManifestError.Parse("Plugin header missing name=")
            val version = field(header, "version")
                ?: throw ManifestError.Parse("Plugin header missing version=")
            if (!ID_PATTERN.matches(id)) {
                throw ManifestError.Parse("Plugin id must match [a-z0-9._-]: '$id'")
            }
            if (name.isBlank()) throw ManifestError.Parse("Plugin name must not be blank")
            if (version.isBlank()) throw ManifestError.Parse("Plugin version must not be blank")

            val wasmBytes = extractWasm(lines)
            return JsPluginManifest(
                id = id,
                name = name,
                version = version,
                jsSource = source,
                wasmBytes = wasmBytes,
            )
        }

        private fun field(header: String, key: String): String? {
            val pattern = Regex("$key=(\"[^\"]*\"|\\S+)")
            val raw = pattern.find(header)?.groupValues?.getOrNull(1) ?: return null
            return raw.removeSurrounding("\"")
        }

        private fun extractWasm(lines: List<String>): ByteArray? {
            val begin = lines.indexOfFirst { WASM_BEGIN in it }
            if (begin < 0) return null
            val end = lines.indexOfFirst { WASM_END in it }
            if (end < 0 || end <= begin) {
                throw ManifestError.Parse("Plugin has '$WASM_BEGIN' without '$WASM_END'")
            }
            val base64 = lines.subList(begin + 1, end)
                .map { it.removePrefix("//").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("")
            if (base64.isEmpty()) throw ManifestError.Parse("Plugin WASM section is empty")
            return runCatching {
                java.util.Base64.getMimeDecoder().decode(base64)
            }.getOrElse {
                throw ManifestError.Parse("Plugin WASM section is not valid base64")
            }
        }
    }
}
