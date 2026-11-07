package com.vayunmathur.games.voxels.network

import com.vayunmathur.library.network.SimpleResponse
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory stand-in for the voxels relay (`/register|/getkey|/append|/pull`),
 * installed via [VoxelsSync.relayTransport] so tests never touch the network.
 * Mirrors the relay contract: append-only channel logs, seq = log size.
 */
object FakeVoxelsRelay {
    val keys = mutableMapOf<String, String>()
    val logs = mutableMapOf<String, MutableList<String>>()

    fun install() {
        VoxelsSync.relayTransport = { path, bodyJson -> handle(path, bodyJson) }
    }

    fun uninstall() {
        VoxelsSync.relayTransport = null
    }

    fun reset() {
        keys.clear(); logs.clear()
    }

    private fun resp(status: Int, body: String) = SimpleResponse(status, "", body, emptyMap(), "fake://relay")

    private fun handle(path: String, bodyJson: String): SimpleResponse {
        val body = JSONObject(bodyJson)
        return when (path) {
            "/register" -> {
                keys[body.getString("id")] = body.getString("key")
                resp(200, "{}")
            }
            "/getkey" -> {
                val key = keys[body.getString("id")]
                if (key != null) resp(200, key) else resp(404, "")
            }
            "/append" -> {
                val channel = body.getString("channel")
                val arr = body.getJSONArray("actions")
                val log = logs.getOrPut(channel) { mutableListOf() }
                for (i in 0 until arr.length()) log += arr.getString(i)
                resp(200, JSONObject().put("seq", log.size).toString())
            }
            "/pull" -> {
                val channel = body.getString("channel")
                val since = body.optInt("since", 0)
                val log = logs[channel].orEmpty()
                val actions = JSONArray()
                log.drop(since).forEach { actions.put(it) }
                resp(200, JSONObject().put("actions", actions).put("seq", log.size).toString())
            }
            else -> resp(404, "")
        }
    }
}
