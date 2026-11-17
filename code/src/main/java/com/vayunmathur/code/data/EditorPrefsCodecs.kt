package com.vayunmathur.code.data
import org.json.JSONArray
import org.json.JSONObject
import com.vayunmathur.code.domain.UserSnippet

/** JSON codecs for the snippet list and fold state stored in [EditorPrefs]. */
internal fun encodeSnippets(snippets: List<UserSnippet>): String {
    val array = JSONArray()
    for (s in snippets) {
        val obj = JSONObject()
            .put("trigger", s.trigger)
            .put("template", s.template)
        if (s.languageId != null) obj.put("lang", s.languageId)
        array.put(obj)
    }
    return array.toString()
}

internal fun decodeSnippets(raw: String?): List<UserSnippet> {
    if (raw.isNullOrEmpty()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            UserSnippet(
                trigger = obj.optString("trigger"),
                template = obj.optString("template"),
                languageId = if (obj.has("lang")) obj.getString("lang") else null,
            )
        }
    }.getOrDefault(emptyList())
}

internal fun encodeFoldState(state: Map<String, List<Int>>): String {
    val obj = JSONObject()
    for ((path, lines) in state) obj.put(path, JSONArray(lines))
    return obj.toString()
}

internal fun decodeFoldState(raw: String?): Map<String, List<Int>> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return runCatching {
        val obj = JSONObject(raw)
        buildMap {
            for (key in obj.keys()) {
                val arr = obj.getJSONArray(key)
                put(key, (0 until arr.length()).map { arr.getInt(it) })
            }
        }
    }.getOrDefault(emptyMap())
}
