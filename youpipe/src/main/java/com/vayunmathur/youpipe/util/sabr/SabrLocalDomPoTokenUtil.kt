package com.vayunmathur.youpipe.util.sabr

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import java.util.Base64

internal data class SabrAttChallengeData(
    val program: String,
    val globalName: String,
    val interpreterUrl: String,
)

internal fun parseSabrAttChallengeData(rawAttestationData: String): SabrAttChallengeData {
    val challenge = JsonParser.`object`().from(rawAttestationData).getObject("bgChallenge")
    val interpreterUrl = challenge.getObject("interpreterUrl")
        .getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
    return SabrAttChallengeData(
        program = challenge.getString("program"),
        globalName = challenge.getString("globalName"),
        interpreterUrl = if (interpreterUrl.startsWith("//")) {
            "https:$interpreterUrl"
        } else {
            interpreterUrl
        },
    )
}

internal fun buildSabrAttChallengeData(
    challengeData: SabrAttChallengeData,
    interpreterJavascript: String,
): String {
    return JsonWriter.string(
        JsonObject.builder()
            .`object`("interpreterJavascript")
            .value("privateDoNotAccessOrElseSafeScriptWrappedValue", interpreterJavascript)
            .value(
                "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",
                challengeData.interpreterUrl,
            )
            .end()
            .value("program", challengeData.program)
            .value("globalName", challengeData.globalName)
            .done(),
    )
}

internal fun parseSabrIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JsonParser.array().from(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData.getString(0)) to integrityTokenData.getLong(1)
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
