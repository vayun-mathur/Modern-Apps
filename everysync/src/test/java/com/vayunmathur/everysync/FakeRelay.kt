package com.vayunmathur.everysync

import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * In-memory fake of the personal relay, installed through
 * [RelaySync.transportForTest] so no test ever touches the network (the CI
 * box has internet, so "expects offline" tests would be wrong there).
 *
 * Implements the four relay endpoints with the same semantics RelaySync
 * relies on: append-only channel logs, `since` slicing on pull, a key
 * directory fed by `/register`, and HTTP status signalling (404 = no key).
 */
class FakeRelay {
    val channels = linkedMapOf<String, MutableList<String>>()
    val registeredKeys = linkedMapOf<String, String>()
    var httpStatus = 200
    var transportDown = false

    fun install() {
        RelaySync.transportForTest = { path, body -> handle(path, body) }
    }

    fun uninstall() {
        RelaySync.transportForTest = null
    }

    fun channel(name: String): List<String> = channels[name].orEmpty().toList()

    private fun ok(body: String) = SimpleResponse(200, "OK", body, emptyMap(), "fake://relay")

    private fun handle(path: String, bodyJson: String): SimpleResponse? {
        if (transportDown) return null
        if (httpStatus != 200) return SimpleResponse(httpStatus, "ERR", "", emptyMap(), "fake://relay")
        val body = Json.parseToJsonElement(bodyJson).jsonObject
        return when (path) {
            "/register" -> {
                registeredKeys[body["id"]!!.jsonPrimitive.content] = body["key"]!!.jsonPrimitive.content
                ok("{}")
            }
            "/getkey" -> {
                val bundle = registeredKeys[runCatching { body["id"]!!.jsonPrimitive.content }.getOrNull()]
                if (bundle == null) SimpleResponse(404, "Not Found", "", emptyMap(), "fake://relay") else ok(bundle)
            }
            "/append" -> {
                val channel = body["channel"]!!.jsonPrimitive.content
                val actions = body["actions"]!!.jsonArray.map { it.jsonPrimitive.content }
                val log = channels.getOrPut(channel) { mutableListOf() }
                log += actions
                ok(buildJsonObject { put("seq", log.size) }.toString())
            }
            "/pull" -> {
                val channel = body["channel"]!!.jsonPrimitive.content
                val since = body["since"]!!.jsonPrimitive.content.toInt()
                val log = channels[channel].orEmpty()
                val slice = log.drop(since.coerceIn(0, log.size))
                ok(
                    buildJsonObject {
                        put("actions", JsonArray(slice.map { JsonPrimitive(it) }))
                        put("seq", log.size)
                    }.toString(),
                )
            }
            else -> SimpleResponse(404, "Not Found", "", emptyMap(), "fake://relay")
        }
    }
}
