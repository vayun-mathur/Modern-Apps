package com.vayunmathur.camera.domain

/**
 * WiFi QR code parsing for the camera scanner. Pure JVM (no Android
 * dependency) so it stays unit-testable.
 *
 * Standard payload (ZXing WiFi format):
 * `WIFI:T:<security>;S:<ssid>;P:<password>;H:<hidden>;;`
 *
 * Fields may appear in any order, the trailing `;;` is optional, and
 * `\` escapes the next character inside a value (`\;`, `\,`, `\:`, `\\`
 * are the ones generators emit). The password is held only in memory by
 * callers — never log or persist a [WifiQrConfig].
 */

/** Security mode declared by a WiFi QR's `T:` field. */
enum class WifiSecurity {
    /** WPA/WPA2/WPA3 personal (PSK/SAE). */
    WPA,

    /** Legacy WEP. Android's suggestion API cannot express WEP. */
    WEP,

    /** Open network (`T:nopass`, empty or missing `T:`). */
    OPEN,
}

/** A parsed WiFi QR payload. [password] is null for open networks. */
data class WifiQrConfig(
    val ssid: String,
    val password: String?,
    val security: WifiSecurity,
    val hidden: Boolean,
)

/** How the platform layer can act on a [WifiQrConfig]. */
enum class WifiSuggestionPlan {
    /** Open network: suggest with no passphrase. */
    OPEN,

    /** WPA personal: suggest with a WPA2/WPA3 passphrase. */
    WPA_PASSPHRASE,

    /** No suggestion-API equivalent (WEP): fall back to WiFi settings. */
    UNSUPPORTED,
}

/** The suggestion-API shape for this config; pure so it is unit-testable. */
fun WifiQrConfig.suggestionPlan(): WifiSuggestionPlan = when (security) {
    WifiSecurity.OPEN -> WifiSuggestionPlan.OPEN
    WifiSecurity.WPA -> WifiSuggestionPlan.WPA_PASSPHRASE
    WifiSecurity.WEP -> WifiSuggestionPlan.UNSUPPORTED
}

/** Whether [text] is a WiFi QR payload the scanner can act on. */
fun isWifiQr(text: String): Boolean = parseWifiQr(text) != null

/**
 * Parses [text] as a WiFi QR payload, or returns null when it is not one
 * (wrong prefix, no SSID, a secured network with no password, or an
 * enterprise/EAP config the suggestion API cannot express from a QR).
 */
fun parseWifiQr(text: String): WifiQrConfig? {
    val trimmed = text.trim()
    if (!trimmed.startsWith(WIFI_PREFIX, ignoreCase = true)) return null
    val fields = splitFields(trimmed.substring(WIFI_PREFIX.length)) ?: return null
    val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
    val security = parseSecurity(fields["T"]) ?: return null
    val password = fields["P"]?.takeIf { it.isNotEmpty() }
    if (security != WifiSecurity.OPEN && password == null) return null
    val hidden = fields["H"]?.let { value ->
        value.equals("true", ignoreCase = true) || value == "1" || value.equals("yes", ignoreCase = true)
    } ?: false
    return WifiQrConfig(
        ssid = ssid,
        password = if (security == WifiSecurity.OPEN) null else password,
        security = security,
        hidden = hidden,
    )
}

private const val WIFI_PREFIX = "WIFI:"

/** Maps the `T:` value to a [WifiSecurity]; null for enterprise/EAP. */
private fun parseSecurity(raw: String?): WifiSecurity? {
    val value = raw?.trim()?.uppercase().orEmpty()
    return when {
        value.isEmpty() || value == "NOPASS" || value == "NONE" -> WifiSecurity.OPEN
        value == "WEP" -> WifiSecurity.WEP
        value.contains("EAP") -> null
        value.startsWith("WPA") || value.startsWith("SAE") -> WifiSecurity.WPA
        else -> null
    }
}

/**
 * Splits the field body on unescaped `;` and each field on its first
 * unescaped `:`, unescaping values as it goes. Returns null on a dangling
 * escape. Unknown keys are kept (callers ignore them); fields without a
 * key separator are skipped, matching generator quirks in the wild.
 */
private fun splitFields(body: String): Map<String, String>? {
    val fields = LinkedHashMap<String, String>()
    for (rawField in splitUnescaped(body, ';') ?: return null) {
        if (rawField.isEmpty()) continue
        val colon = indexOfUnescaped(rawField, ':')
        if (colon <= 0) continue
        val key = rawField.substring(0, colon).uppercase()
        val value = unescape(rawField.substring(colon + 1)) ?: return null
        fields[key] = value
    }
    return fields
}

/** Splits on unescaped [delimiter], keeping escape sequences intact. */
private fun splitUnescaped(text: String, delimiter: Char): List<String>? {
    val parts = ArrayList<String>()
    val current = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' -> {
                if (i + 1 >= text.length) return null
                current.append(c).append(text[i + 1])
                i += 2
            }
            c == delimiter -> {
                parts.add(current.toString())
                current.clear()
                i++
            }
            else -> {
                current.append(c)
                i++
            }
        }
    }
    parts.add(current.toString())
    return parts
}

/** Index of the first unescaped [target], or -1. */
private fun indexOfUnescaped(text: String, target: Char): Int {
    var i = 0
    while (i < text.length) {
        when {
            text[i] == '\\' -> i += 2
            text[i] == target -> return i
            else -> i++
        }
    }
    return -1
}

/** Removes `\` escapes (`\;` -> `;`, `\\` -> `\`, ...); null on a dangling `\`. */
private fun unescape(text: String): String? {
    if (!text.contains('\\')) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (text[i] == '\\') {
            if (i + 1 >= text.length) return null
            out.append(text[i + 1])
            i += 2
        } else {
            out.append(text[i])
            i++
        }
    }
    return out.toString()
}
