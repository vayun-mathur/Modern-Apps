package com.vayunmathur.games.wordmaker.util

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class Dictionary private constructor(
    private val wordSet: Set<String>,
    private val definitions: Map<String, List<String>>
) {
    operator fun contains(word: String) = word.lowercase() in wordSet

    fun getDefinition(word: String): List<String> = definitions[word.lowercase()] ?: emptyList()

    companion object {
        val EMPTY = Dictionary(emptySet(), emptyMap())

        private val cache = mutableMapOf<String, Dictionary>()
        private val remoteCache = mutableMapOf<String, List<String>>()
        private val json = Json { ignoreUnknownKeys = true }

        private val httpClient by lazy { HttpClient(CIO) }

        suspend fun fetchRemoteDefinition(word: String): List<String> {
            val key = word.lowercase()
            remoteCache[key]?.let { return it }
            return try {
                val text = httpClient.get(
                    "https://en.wiktionary.org/api/rest_v1/page/definition/$key"
                ).bodyAsText()
                val root = json.parseToJsonElement(text).jsonObject
                val deEntries = root["de"]?.jsonArray ?: return emptyList()
                val result = deEntries.mapNotNull { entry ->
                    val obj = entry.jsonObject
                    val type = obj["type"]?.jsonPrimitive?.content
                        ?: obj["partOfSpeech"]?.jsonPrimitive?.content
                        ?: ""
                    val defs = obj["definitions"]?.jsonArray?.mapNotNull { defEntry ->
                        defEntry.jsonObject["definition"]?.jsonPrimitive?.content
                            ?.let { stripHtml(it) }
                            ?.takeIf { it.isNotBlank() }
                    }.orEmpty()
                    if (defs.isEmpty()) null
                    else buildString {
                        if (type.isNotBlank()) appendLine(type)
                        append(defs.joinToString("\n"))
                    }
                }
                if (result.isNotEmpty()) remoteCache[key] = result
                result
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun stripHtml(html: String) =
            html.replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<[^>]+>"), "")
                .trim()

        fun load(context: Context, fileName: String): Dictionary {
            cache[fileName]?.let { return it }
            return try {
                if (fileName.endsWith(".txt")) loadWordList(context, fileName)
                else loadCsv(context, fileName)
            } catch (_: Exception) {
                EMPTY
            }.also { cache[fileName] = it }
        }

        private fun loadWordList(context: Context, fileName: String): Dictionary {
            val wordSet = mutableSetOf<String>()
            context.assets.open(fileName).bufferedReader().forEachLine { line ->
                val word = line.trim().lowercase()
                if (word.isNotEmpty()) wordSet.add(word)
            }
            return Dictionary(wordSet, emptyMap())
        }

        private fun loadCsv(context: Context, fileName: String): Dictionary {
            val wordSet = mutableSetOf<String>()
            val definitions = mutableMapOf<String, MutableList<String>>()
            context.assets.open(fileName).bufferedReader().forEachLine { line ->
                val parts = line.split(",", limit = 4)
                if (parts.size < 4) return@forEachLine
                val word = parts[0].lowercase()
                wordSet.add(word)
                definitions.getOrPut(word) { mutableListOf() }
                    .add(parts[3].trim { it == '"' })
            }
            return Dictionary(wordSet, definitions)
        }
    }
}
