package com.vayunmathur.games.wordmaker.util

import android.content.Context

class Dictionary private constructor(
    private val wordSet: Set<String>,
    private val definitions: Map<String, List<String>>
) {
    operator fun contains(word: String) = word.lowercase() in wordSet

    fun getDefinition(word: String): List<String> = definitions[word.lowercase()] ?: emptyList()

    companion object {
        val EMPTY = Dictionary(emptySet(), emptyMap())

        private val cache = mutableMapOf<String, Dictionary>()

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
