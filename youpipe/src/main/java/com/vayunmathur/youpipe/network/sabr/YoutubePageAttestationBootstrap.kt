package com.vayunmathur.youpipe.network.sabr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString

internal enum class YoutubePoTokenBinding {
    CONTENT,
    SESSION,
    NONE,
}

internal data class YoutubePageAttestationBootstrap(
    val visitorData: String,
    val clientName: String,
    val clientVersion: String,
    val binding: YoutubePoTokenBinding,
    val eventId: String,
    val challenge: SabrAttChallengeData,
)

internal data class SabrAttChallengeData(
    val program: String,
    val globalName: String,
    val interpreterJavascript: String?,
    val interpreterUrl: String?,
)

private val bootstrapJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Parses the attestation bootstrap from YouTube home page HTML (`https://www.youtube.com`).
 *
 * Port of PipePipe's `parseYoutubePageAttestationBootstrap`: the `ytcfg.set({...})` calls carry
 * the visitor data, client version and EVENT_ID, and the `window.ytAtN({...})` call carries the
 * initial BotGuard attestation challenge. Minting against this page-bound challenge (instead of
 * the old `youtubei/v1/att/get` UNBOUND flow) is what makes the server accept the PO token
 * (#565: without it every SABR response stays `status=2` attestation-pending).
 */
@Throws(SabrProtocolException::class)
internal fun parseYoutubePageAttestationBootstrap(pageHtml: String): YoutubePageAttestationBootstrap {
    val challengeCall = extractObjectCallArguments(pageHtml, INITIAL_ATTESTATION_CALLEE)
        .asSequence()
        .mapNotNull { call -> parseInitialAttestationChallenge(call.argument)?.let { call to it } }
        .firstOrNull()
        ?: throw SabrProtocolException("YouTube home has no initial attestation challenge")
    val clientConfig = findClientConfig(pageHtml, challengeCall.first.start)
    val eventId = findEventId(pageHtml, challengeCall.first.start)
    val visitorData = extractVisitorData(clientConfig)
    val (clientName, clientVersion) = extractClientIdentity(clientConfig)
    val binding = resolveBinding(clientConfig)
    return YoutubePageAttestationBootstrap(
        visitorData,
        clientName,
        clientVersion,
        binding,
        eventId,
        challengeCall.second,
    )
}

@Throws(SabrProtocolException::class)
private fun findClientConfig(pageHtml: String, challengeStart: Int): JsonObject {
    val configs = extractObjectCallArguments(pageHtml, YTCFG_CALLEE)
        .mapNotNull { call -> parseConfigCall(call) }
        .filter { (call) -> call.start < challengeStart }
        .map { (_, config) -> config }
    return configs.lastOrNull { it.has("INNERTUBE_CONTEXT") }
        ?: throw SabrProtocolException("YouTube home has no client context")
}

private fun parseConfigCall(call: JavascriptObjectCall): Pair<JavascriptObjectCall, JsonObject>? {
    return try {
        call to bootstrapJson.parseToJsonElement(call.argument).jsonObject
    } catch (_: IllegalArgumentException) {
        // One malformed ytcfg.set among many: skip it, others may parse.
        null
    }
}

@Throws(SabrProtocolException::class)
private fun findEventId(pageHtml: String, challengeStart: Int): String {
    return extractObjectCallArguments(pageHtml, YTCFG_CALLEE)
        .mapNotNull { call -> parseConfigCall(call) }
        .filter { (call) -> call.start < challengeStart }
        .map { (_, config) -> config }
        .asSequence()
        .mapNotNull { it.getString("EVENT_ID")?.takeIf(String::isNotEmpty) }
        .lastOrNull()
        ?: throw SabrProtocolException("YouTube home has no EVENT_ID")
}

@Throws(SabrProtocolException::class)
private fun extractVisitorData(clientConfig: JsonObject): String {
    return (
        clientConfig.getString("EOM_VISITOR_DATA")?.takeIf(String::isNotEmpty)
            ?: clientConfig.getString("VISITOR_DATA")?.takeIf(String::isNotEmpty)
        )?.replace("%3D", "=", ignoreCase = true)
        ?: throw SabrProtocolException("YouTube home has no anonymous visitor data")
}

@Throws(SabrProtocolException::class)
private fun extractClientIdentity(clientConfig: JsonObject): Pair<String, String> {
    val client = clientConfig.getObject("INNERTUBE_CONTEXT")?.getObject("client")
        ?: throw SabrProtocolException("YouTube home has no Innertube client context")
    val clientName = requireClientName(client)
    val clientVersion = client.getString("clientVersion")?.takeIf(String::isNotEmpty)
        ?: throw SabrProtocolException("YouTube home has no client version")
    return clientName to clientVersion
}

@Throws(SabrProtocolException::class)
private fun requireClientName(client: JsonObject): String {
    val clientName = client.getString("clientName")?.takeIf(String::isNotEmpty)
        ?: throw SabrProtocolException("YouTube home has no client name")
    if (clientName != WEB_CLIENT_NAME) {
        throw SabrProtocolException("Unsupported YouTube home client: $clientName")
    }
    return clientName
}

private fun resolveBinding(clientConfig: JsonObject): YoutubePoTokenBinding {
    val watchConfig = clientConfig.getObject("WEB_PLAYER_CONTEXT_CONFIGS")
        ?.getObject("WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH")
    val experimentFlags = watchConfig?.getString("serializedExperimentFlags")
        ?.let(::parseExperimentFlags)
        .orEmpty()
    return when {
        experimentFlags["html5_generate_content_po_token"] == "true" -> YoutubePoTokenBinding.CONTENT
        experimentFlags["html5_generate_session_po_token"] == "true" -> YoutubePoTokenBinding.SESSION
        watchConfig == null -> YoutubePoTokenBinding.CONTENT
        else -> YoutubePoTokenBinding.NONE
    }
}

private fun parseInitialAttestationChallenge(argument: String): SabrAttChallengeData? {
    val responseProperty = INITIAL_ATTESTATION_RESPONSE.find(argument) ?: return null
    val quote = responseProperty.groupValues[1].single()
    val rawChallenge = try {
        decodeJavascriptString(argument, responseProperty.range.last + 1, quote)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return try {
        parseSabrAttChallengeData(rawChallenge)
    } catch (_: IllegalArgumentException) {
        // Malformed challenge JSON among candidates: skip it, others may parse.
        null
    }
}

internal fun parseSabrAttChallengeData(rawAttestationData: String): SabrAttChallengeData {
    val challenge = parseChallengeObject(rawAttestationData)
    val (interpreterJavascript, interpreterUrl) = extractInterpreter(challenge)
    val program = challenge.getString("program")?.takeIf(String::isNotEmpty)
        ?: throw SabrProtocolException("Attestation challenge has no program")
    val globalName = challenge.getString("globalName")?.takeIf(String::isNotEmpty)
        ?: throw SabrProtocolException("Attestation challenge has no global name")
    return SabrAttChallengeData(program, globalName, interpreterJavascript, interpreterUrl)
}

@Throws(SabrProtocolException::class)
private fun extractInterpreter(challenge: JsonObject): Pair<String?, String?> {
    val interpreterJavascript = challenge.getObject("interpreterJavascript")
        ?.getString("privateDoNotAccessOrElseSafeScriptWrappedValue")
        ?.takeIf(String::isNotEmpty)
    val rawInterpreterUrl = challenge.getObject("interpreterUrl")
        ?.getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
        ?.takeIf(String::isNotEmpty)
    val interpreterUrl = rawInterpreterUrl?.let {
        if (it.startsWith("//")) "https:$it" else it
    }
    if (interpreterJavascript == null && interpreterUrl == null) {
        throw SabrProtocolException("Attestation challenge has no interpreter script or URL")
    }
    return interpreterJavascript to interpreterUrl
}

@Throws(SabrProtocolException::class)
private fun parseChallengeObject(rawAttestationData: String): JsonObject {
    val challenge = try {
        bootstrapJson.parseToJsonElement(rawAttestationData).jsonObject.getObject("bgChallenge")
    } catch (e: IllegalArgumentException) {
        throw SabrProtocolException("Attestation response has no BotGuard challenge", e)
    } ?: throw SabrProtocolException("Attestation response has no BotGuard challenge")
    return challenge
}

private data class JavascriptObjectCall(
    val start: Int,
    val argument: String,
)

private fun extractObjectCallArguments(
    source: String,
    callee: String,
): List<JavascriptObjectCall> {
    val arguments = ArrayList<JavascriptObjectCall>()
    var searchFrom = 0
    while (true) {
        val callStart = source.indexOf(callee, searchFrom)
        if (callStart < 0) return arguments
        val nextSearch = extractOneCall(source, callee, callStart, arguments)
        if (nextSearch < 0) return arguments
        searchFrom = nextSearch
    }
}

/** Extracts the single call at [callStart] if well-formed; returns the next search offset. */
private fun extractOneCall(
    source: String,
    callee: String,
    callStart: Int,
    arguments: MutableList<JavascriptObjectCall>,
): Int {
    var openingParenthesis = callStart + callee.length
    while (openingParenthesis < source.length && source[openingParenthesis].isWhitespace()) {
        openingParenthesis++
    }
    if (openingParenthesis >= source.length || source[openingParenthesis] != '(') {
        return callStart + callee.length
    }
    var objectStart = openingParenthesis + 1
    while (objectStart < source.length && source[objectStart].isWhitespace()) objectStart++
    if (objectStart >= source.length || source[objectStart] != '{') {
        return openingParenthesis + 1
    }
    val objectEnd = findJavascriptObjectEnd(source, objectStart)
    if (objectEnd < 0) {
        return objectStart + 1
    }
    arguments.add(JavascriptObjectCall(callStart, source.substring(objectStart, objectEnd + 1)))
    return objectEnd + 1
}

private fun findJavascriptObjectEnd(source: String, start: Int): Int {
    var depth = 0
    var quote = '\u0000'
    var escaped = false
    for (index in start until source.length) {
        val character = source[index]
        if (quote != '\u0000') {
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == quote) {
                quote = '\u0000'
            }
            continue
        }
        when (character) {
            '\'', '"' -> quote = character
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    return -1
}

private fun parseExperimentFlags(serializedFlags: String): Map<String, String> {
    return serializedFlags.split('&').associate { part ->
        val separator = part.indexOf('=')
        if (separator < 0) part to "true"
        else part.substring(0, separator) to part.substring(separator + 1)
    }
}

private fun decodeJavascriptString(source: String, start: Int, quote: Char): String {
    val result = StringBuilder()
    var index = start
    while (index < source.length) {
        val character = source[index++]
        if (character == quote) return result.toString()
        if (character != '\\') {
            result.append(character)
            continue
        }
        require(index < source.length) { "Incomplete JavaScript string escape" }
        index = appendJavascriptEscape(source, index, result)
    }
    throw IllegalArgumentException("Unterminated JavaScript string")
}

/** Appends the escape at [index] to [result]; returns the index after the escape. */
private fun appendJavascriptEscape(source: String, index: Int, result: StringBuilder): Int {
    var next = index
    when (val escaped = source[next++]) {
        'b' -> result.append('\b')
        'f' -> result.append('\u000C')
        'n' -> result.append('\n')
        'r' -> result.append('\r')
        't' -> result.append('\t')
        'v' -> result.append('\u000B')
        'x' -> {
            result.append(readJavascriptHex(source, next, HEX_ESCAPE_LENGTH).toChar())
            next += HEX_ESCAPE_LENGTH
        }
        'u' -> {
            result.append(readJavascriptHex(source, next, UNICODE_ESCAPE_LENGTH).toChar())
            next += UNICODE_ESCAPE_LENGTH
        }
        '\n' -> Unit
        '\r' -> if (next < source.length && source[next] == '\n') next++
        else -> result.append(escaped)
    }
    return next
}

private fun readJavascriptHex(source: String, start: Int, length: Int): Int {
    require(start + length <= source.length) { "Incomplete hexadecimal escape" }
    var value = 0
    repeat(length) { offset ->
        val digit = source[start + offset].digitToIntOrNull(HEX_RADIX)
            ?: throw IllegalArgumentException("Invalid hexadecimal escape")
        value = value * HEX_RADIX + digit
    }
    return value
}

private fun JsonObject.has(key: String): Boolean = containsKey(key)

private const val YTCFG_CALLEE = "ytcfg.set"
private const val INITIAL_ATTESTATION_CALLEE = "window.ytAtN"
private const val WEB_CLIENT_NAME = "WEB"
private const val HEX_ESCAPE_LENGTH = 2
private const val UNICODE_ESCAPE_LENGTH = 4
private const val HEX_RADIX = 16
private val INITIAL_ATTESTATION_RESPONSE = Regex("['\"]R['\"]\\s*:\\s*(['\"])")
