package com.vayunmathur.youpipe.network.sabr

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.schabi.newpipe.extractor.utils.JsonUtils
import java.util.Base64

/**
 * Builds the challenge payload handed to `pipepipeSabrRunBotguard` in `sabr_po_token.js`.
 * Only the interpreter payload the page actually inlined is sent; the trusted URL is a hint
 * the JS does not consume.
 */
internal fun buildSabrAttChallengeData(
    challengeData: SabrAttChallengeData,
    interpreterJavascript: String,
): String {
    return buildJsonObject {
        putJsonObject("interpreterJavascript") {
            put("privateDoNotAccessOrElseSafeScriptWrappedValue", interpreterJavascript)
        }
        put("program", challengeData.program)
        put("globalName", challengeData.globalName)
    }.toString()
}

internal fun parseSabrIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JsonUtils.toJsonArray(rawIntegrityTokenData)
    // A short array from the GenerateIT endpoint is an argument failure
    // the caller already handles; indexing it would crash the raw thread.
    require(integrityTokenData.size >= 2) { "Integrity token data has fewer than 2 elements" }
    return base64ToU8(integrityTokenData[0].jsonPrimitive.content) to
        integrityTokenData[1].jsonPrimitive.content.toLong()
}

internal fun stringToSabrU8(value: String): String {
    return newUint8Array(value.toByteArray())
}

internal fun csvU8ToByteArray(value: String): ByteArray {
    if (value.isBlank()) {
        return ByteArray(0)
    }
    return value.split(",").map { it.toUByte().toByte() }.toByteArray()
}

private fun base64ToU8(base64: String): String {
    return newUint8Array(base64ToByteArray(base64))
}

private fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" + contents.joinToString(separator = ",") {
        it.toUByte().toString()
    } + "])"
}

private fun base64ToByteArray(base64: String): ByteArray {
    val normalized = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')
    return Base64.getDecoder().decode(normalized)
}
