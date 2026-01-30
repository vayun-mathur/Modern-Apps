package com.vayunmathur.games.wordmaker

import android.content.Context

class Dictionary() {
    data class Word(val word: String, val position: String, val definition: String)
    private val words = mutableListOf<Word>()
    private val definitions: MutableMap<String, List<String>> = mutableMapOf()

    fun init(context: Context) {
        context.assets.open("dictionary.csv").bufferedReader().lines().forEach {
            val parts = it.split(",", limit = 4)
            if(parts.size < 4) return@forEach
            words.add(Word(parts[0].lowercase(), parts[2], parts[3].trim { it == '"' }))
            definitions[parts[0].lowercase()] = (definitions[parts[0].lowercase()] ?: listOf()) + parts[3].trim { it == '"' }
        }
    }

    operator fun contains(word: String): Boolean {
        return words.any { it.word == word }
    }

    fun getDefinition(word: String): List<String> {
        return definitions[word.lowercase()] ?: listOf()
    }
}